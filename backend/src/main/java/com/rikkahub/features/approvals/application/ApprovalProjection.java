package com.rikkahub.features.approvals.application;

import java.util.List;

import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.event.ApprovalAskedEvent;
import com.rikkahub.agent.session.event.ApprovalDecidedEvent;
import com.rikkahub.agent.session.event.ToolCallEvent;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.ProjectionContext;
import com.rikkahub.platform.storage.SessionProjection;
import org.springframework.stereotype.Component;

/**
 * Durable approval projection. It joins the session commit transaction, so a
 * projection failure rolls the asked event back instead of stranding a
 * decision.
 * It never writes session events and never executes tools.
 */
@Component
public final class ApprovalProjection implements SessionProjection {
    private final EventCodec codec;

    public ApprovalProjection(EventCodec codec) {
        this.codec = codec;
    }

    @Override
    public void apply(SessionId sessionId, List<SessionEvent> events, ProjectionContext context) {
        for (SessionEvent event : events) {
            if (event.data() instanceof ApprovalAskedEvent asked)
                insert(sessionId, asked, event.seq(), context);
            else if (event.data() instanceof ApprovalDecidedEvent decided)
                settle(decided, event.seq(), context);
        }
    }

    private void insert(SessionId sessionId, ApprovalAskedEvent asked, long seq, ProjectionContext context) {
        String arguments = argumentsOf(context, asked.callId(), seq);
        String at = EventCodec.timestamp(context.committedAt());
        context.jdbc().sql("""
                INSERT INTO approvals(id,session_id,call_id,tool_name,reason,turn,arguments_json,arguments_digest,
                        status,asked_seq,expires_at,created_at,updated_at)
                VALUES(:id,:session,:call,:tool,:reason,:turn,:arguments,:digest,'PENDING',:seq,:expiry,:at,:at)
                """).param("id", asked.approvalId()).param("session", sessionId.value())
                .param("call", asked.callId()).param("tool", asked.toolName()).param("reason", asked.reason())
                .param("turn", asked.turn()).param("arguments", arguments).param("digest", asked.argumentsDigest())
                .param("seq", seq)
                .param("expiry", asked.expiresAt() == null ? null : EventCodec.timestamp(asked.expiresAt()))
                .param("at", at).update();
    }

    /**
     * The asked payload binds identity and digest; the argument text itself lives
     * in
     * the session log. The bounded call lookup keeps this projection read short.
     */
    private String argumentsOf(ProjectionContext context, String callId, long askedSeq) {
        // Match the exact call instead of scanning a fixed recent window: a single
        // assistant response may emit more than 64 tool calls, and the approver must
        // see the raw arguments of the call they are authorizing.
        return context.jdbc().sql("""
                SELECT data_json FROM session_events
                WHERE session_id=:id AND event_type='tool/call' AND seq<:seq
                      AND json_extract(data_json,'$.callId')=:call
                ORDER BY seq DESC LIMIT 1
                """).param("id", context.header().id().value()).param("seq", askedSeq)
                .param("call", callId).query(String.class).optional()
                .map(row -> codec.decode(row, ToolCallEvent.class).argumentsJson())
                .orElse("");
    }

    private void settle(ApprovalDecidedEvent decided, long seq, ProjectionContext context) {
        context.jdbc().sql("""
                UPDATE approvals SET status=:decision, decided_seq=:seq, decision_note=:note, updated_at=:at
                WHERE id=:id AND status='PENDING'
                """).param("decision", decided.decision()).param("seq", seq).param("note", decided.note())
                .param("at", EventCodec.timestamp(context.committedAt())).param("id", decided.approvalId()).update();
    }
}
