package com.rikkahub.features.chat.infrastructure.persistence;

import java.util.List;
import java.util.ConcurrentModificationException;
import tools.jackson.core.type.TypeReference;
import com.rikkahub.agent.message.*;
import com.rikkahub.agent.session.*;
import com.rikkahub.agent.session.event.*;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.ProjectionContext;
import com.rikkahub.platform.storage.SessionProjection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SQL query view only; raw session events remain the source of model history.
 */
@Component
public final class MessageProjectionService implements SessionProjection {
    private static final TypeReference<List<ContentBlock>> CONTENT = new TypeReference<>() {
    };
    private final EventCodec codec;

    public MessageProjectionService(EventCodec codec) {
        this.codec = codec;
    }

    @Override
    public void apply(SessionId session, List<SessionEvent> events, ProjectionContext context) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("message projection requires the session commit transaction");
        }
        String conversation = context.header().metadata().get("conversationId");
        if (conversation == null)
            return;
        JdbcClient jdbc = context.jdbc();
        var association = jdbc.sql("SELECT session_id FROM conversations WHERE id=:id").param("id", conversation)
                .query((rs, n) -> new Association(rs.getString(1))).single();
        if (association.session() != null && !association.session().equals(session.value())) {
            throw new IllegalStateException("conversation already belongs to another session");
        }
        long turnStart = context.previousTurnStartSeq();
        for (SessionEvent event : events) {
            if (event.data() instanceof TurnStart) {
                turnStart = event.seq();
            }
            if (event.data() instanceof StepStart) {
                startAssistant(jdbc, session, conversation, event);
            }
            if (event.data() instanceof TurnEnd end) {
                finishPending(jdbc, session, event, end.reason());
            }
            if (event.surface() == null || event.ignorable())
                continue;
            project(jdbc, session, conversation, event, turnStart);
        }
        String current = jdbc.sql(
                "SELECT id FROM message_nodes WHERE conversation_id=:id AND visible=1 ORDER BY surface_position DESC LIMIT 1")
                .param("id", conversation).query(String.class).optional().orElse(null);
        int changed = jdbc.sql("""
                UPDATE conversations SET session_id=:session,last_seq=:last,current_node_id=:node,updated_at=:at
                WHERE id=:id AND (session_id IS NULL OR session_id=:session)
                """).param("session", session.value()).param("last", context.lastSeq()).param("node", current)
                .param("at", EventCodec.timestamp(context.committedAt())).param("id", conversation).update();
        if (changed != 1)
            throw new ConcurrentModificationException("conversation session association changed");
    }

    private void project(JdbcClient jdbc, SessionId session, String conversation, SessionEvent event, long turnStart) {
        String role;
        List<ContentBlock> content;
        String usage = null, callId = null;
        if (event.data() instanceof SystemMessageEvent e) {
            role = "SYSTEM";
            content = List.of(new TextBlock(e.text()));
        } else if (event.data() instanceof UserMessageEvent e) {
            role = "USER";
            content = e.content();
        } else if (event.data() instanceof AssistantMessageEvent e) {
            role = "ASSISTANT";
            content = e.content();
            usage = codec.encode(e.usage());
        } else if (event.data() instanceof ToolResultEvent e) {
            role = "TOOL";
            content = List.of(new ToolResultBlock(e.callId(), e.toolName(), e.content(), e.isError()));
            callId = e.callId();
        } else
            throw new IllegalArgumentException("payload cannot be projected as a message");
        long position = position(jdbc, session, conversation, event);
        String parent = jdbc.sql(
                "SELECT id FROM message_nodes WHERE conversation_id=:id AND visible=1 AND surface_position<:position ORDER BY surface_position DESC LIMIT 1")
                .param("id", conversation).param("position", position).query(String.class).optional().orElse(null);
        String node = null;
        if (role.equals("ASSISTANT")) {
            node = jdbc.sql(
                    "SELECT id FROM message_nodes WHERE session_id=:id AND role='ASSISTANT' AND status='STREAMING' ORDER BY anchor_seq DESC LIMIT 1")
                    .param("id", session.value()).query(String.class).optional().orElse(null);
        }
        String status = event.data() instanceof AssistantMessageEvent a && a.stopReason().equals("CANCELLED")
                ? "INTERRUPTED"
                : "COMPLETE";
        String error = null;
        if (event.data() instanceof ToolResultEvent result && result.isError()) {
            status = "ERROR";
            error = codec.encode(java.util.Map.of("code", "tool_error", "callId", result.callId()));
        }
        if (node == null) {
            node = session.value() + ":m:" + event.seq();
            long anchor = event.seq();
            if (role.equals("USER") && turnStart > 0
                    && jdbc.sql("SELECT count(*) FROM message_nodes WHERE session_id=:id AND anchor_seq=:anchor")
                            .param("id", session.value()).param("anchor", turnStart).query(Integer.class)
                            .single() == 0) {
                anchor = turnStart;
            }
            insertNode(jdbc, node, session, conversation, parent, anchor, event.seq(), event.seq(), role, status, 1,
                    position, event);
        } else {
            jdbc.sql(
                    "UPDATE message_nodes SET source_seq=:seq,end_seq=:seq,parent_id=:parent,status=:status,visible=1,surface_position=:position WHERE id=:id")
                    .param("seq", event.seq()).param("parent", parent).param("status", status)
                    .param("position", position).param("id", node).update();
        }
        List<ContentBlock> calls = content.stream().filter(ToolCallBlock.class::isInstance).toList();
        writeMessage(jdbc, node, conversation, codec.encode(content, CONTENT),
                calls.isEmpty() ? null : codec.encode(calls, CONTENT), callId, usage, error, event);
    }

    private long position(JdbcClient jdbc, SessionId session, String conversation, SessionEvent event) {
        if (event.surface().op() instanceof SurfaceOp.SurfaceReplace replace) {
            Long first = jdbc.sql(
                    "SELECT min(surface_position) FROM message_nodes WHERE session_id=:session AND visible=1 AND source_seq BETWEEN :start AND :end")
                    .param("session", session.value()).param("start", replace.startSeq()).param("end", replace.endSeq())
                    .query((rs, n) -> {
                        long value = rs.getLong(1);
                        return rs.wasNull() ? null : value;
                    }).optional().orElse(null);
            if (first != null) {
                jdbc.sql(
                        "UPDATE message_nodes SET visible=0,hidden_at_seq=:seq WHERE session_id=:session AND visible=1 AND source_seq BETWEEN :start AND :end")
                        .param("seq", event.seq()).param("session", session.value()).param("start", replace.startSeq())
                        .param("end", replace.endSeq()).update();
                // Retain original branch nodes and their ancestry. Only the
                // separate active-surface ordering changes, including middle replacements.
                jdbc.sql("""
                        UPDATE message_nodes SET surface_position=surface_position-
                          (SELECT count(*) FROM message_nodes removed WHERE removed.session_id=:session
                           AND removed.hidden_at_seq=:seq AND removed.surface_position<message_nodes.surface_position)
                          +CASE WHEN surface_position>:first THEN 1 ELSE 0 END
                        WHERE conversation_id=:conversation AND visible=1
                        """).param("session", session.value()).param("seq", event.seq()).param("first", first)
                        .param("conversation", conversation).update();
                return first;
            }
        }
        return nextPosition(jdbc, conversation);
    }

    private void startAssistant(JdbcClient jdbc, SessionId session, String conversation, SessionEvent event) {
        String node = session.value() + ":step:" + event.seq();
        String parent = jdbc.sql(
                "SELECT id FROM message_nodes WHERE conversation_id=:id AND visible=1 ORDER BY surface_position DESC LIMIT 1")
                .param("id", conversation).query(String.class).optional().orElse(null);
        insertNode(jdbc, node, session, conversation, parent, event.seq(), event.seq(), null, "ASSISTANT", "STREAMING",
                0, nextPosition(jdbc, conversation), event);
        writeMessage(jdbc, node, conversation, "[]", null, null, null, null, event);
    }

    private void finishPending(JdbcClient jdbc, SessionId session, SessionEvent event, TurnEndReason reason) {
        String status = reason instanceof TurnEndReason.Errored ? "ERROR" : "INTERRUPTED";
        jdbc.sql(
                "UPDATE messages SET error_json=:error,updated_at=:at WHERE node_id IN (SELECT id FROM message_nodes WHERE session_id=:id AND status='STREAMING')")
                .param("error", codec.encode(reason)).param("at", EventCodec.timestamp(event.time()))
                .param("id", session.value()).update();
        jdbc.sql("UPDATE message_nodes SET status=:status,end_seq=:seq WHERE session_id=:id AND status='STREAMING'")
                .param("status", status).param("seq", event.seq()).param("id", session.value()).update();
    }

    private long nextPosition(JdbcClient jdbc, String conversation) {
        return jdbc.sql(
                "SELECT coalesce(max(surface_position),0)+1 FROM message_nodes WHERE conversation_id=:id AND visible=1")
                .param("id", conversation).query(Long.class).single();
    }

    private void insertNode(JdbcClient jdbc, String node, SessionId session, String conversation, String parent,
            long anchor,
            long source, Long end, String role, String status, int visible, long position, SessionEvent event) {
        jdbc.sql("""
                INSERT INTO message_nodes(id,conversation_id,parent_id,session_id,anchor_seq,source_seq,end_seq,
                role,status,visible,surface_position,created_at)
                VALUES(:id,:conversation,:parent,:session,:anchor,:source,:end,:role,:status,:visible,:position,:at)
                """).param("id", node).param("conversation", conversation).param("parent", parent)
                .param("session", session.value())
                .param("anchor", anchor).param("source", source).param("end", end).param("role", role)
                .param("status", status)
                .param("visible", visible).param("position", position).param("at", EventCodec.timestamp(event.time()))
                .update();
    }

    private void writeMessage(JdbcClient jdbc, String node, String conversation, String content, String calls,
            String callId, String usage, String error, SessionEvent event) {
        jdbc.sql(
                """
                        INSERT INTO messages(node_id,conversation_id,content_json,tool_calls_json,tool_call_id,usage_json,error_json,updated_at)
                        VALUES(:id,:conversation,:content,:calls,:call,:usage,:error,:at)
                        ON CONFLICT(node_id) DO UPDATE SET content_json=excluded.content_json,
                        tool_calls_json=excluded.tool_calls_json,tool_call_id=excluded.tool_call_id,usage_json=excluded.usage_json,error_json=excluded.error_json,updated_at=excluded.updated_at
                        """)
                .param("id", node).param("conversation", conversation).param("content", content).param("calls", calls)
                .param("call", callId).param("usage", usage).param("error", error)
                .param("at", EventCodec.timestamp(event.time())).update();
    }

    private record Association(String session) {
    }
}
