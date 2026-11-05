package com.rikkahub.features.chat.application;

import java.time.Instant;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.api.*;
import com.rikkahub.agent.config.ToolRule;
import com.rikkahub.agent.message.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.port.PendingInput;
import com.rikkahub.agent.session.*;
import com.rikkahub.agent.session.event.SystemMessageEvent;
import com.rikkahub.features.assistants.application.AssistantApplicationService;
import com.rikkahub.features.chat.agent.ChatAgentService;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.idempotency.IdempotencyService;
import com.rikkahub.platform.idempotency.IdempotencyService.*;
import com.rikkahub.platform.storage.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public final class ChatApplicationService {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final SessionCommitService commits;
    private final JdbcInboxStore inbox;
    private final AssistantApplicationService assistants;
    private final ChatAgentService agents;
    private final IdempotencyService idempotency;
    private final StoragePaths paths;
    private final Clock clock;
    private final IdGenerator ids;
    private final SystemPromptService systemPrompts;

    public ChatApplicationService(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec,
            SessionCommitService commits,
            JdbcInboxStore inbox, AssistantApplicationService assistants, ChatAgentService agents,
            IdempotencyService idempotency, StoragePaths paths, Clock clock, IdGenerator ids,
            SystemPromptService systemPrompts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = codec;
        this.commits = commits;
        this.inbox = inbox;
        this.assistants = assistants;
        this.agents = agents;
        this.idempotency = idempotency;
        this.paths = paths;
        this.clock = clock;
        this.ids = ids;
        this.systemPrompts = systemPrompts;
    }

    public Result create(String key, JsonNode body) {
        object(body, Set.of("title", "assistantId"));
        String assistantId = text(body, "assistantId", true, 128);
        String title = text(body, "title", false, 200);
        if (title == null || title.isBlank())
            title = "新对话";
        String finalTitle = title;
        return idempotency.execute("POST:/conversations", key, body, () -> {
            var assistant = assistants.requireInTransaction(assistantId);
            String id = ids.newId("conv");
            SessionId session = ids.newSessionId();
            Instant now = clock.now();
            jdbc.sql(
                    "INSERT INTO conversations(id,title,assistant_id,status,created_at,updated_at) VALUES(:id,:title,:assistant,'ACTIVE',:at,:at)")
                    .param("id", id).param("title", finalTitle).param("assistant", assistantId)
                    .param("at", EventCodec.timestamp(now)).update();
            // P3 workspace isolation: each session owns a directory under data/workspace.
            // Legacy sessions keep their recorded header cwd and resume unchanged.
            java.nio.file.Path workspace = paths.dataDirectory().resolve("workspace").resolve(session.value());
            SessionHeader header = new SessionHeader(session, null, workspace, SessionFormat.CURRENT, "chat",
                    assistantId, Map.of("conversationId", id), now);
            String systemPrompt = systemPrompts.render(assistant, workspace);
            List<NewSessionEvent> seed = systemPrompt.isBlank() ? List.of()
                    : List.of(new NewSessionEvent(new SystemMessageEvent(systemPrompt),
                            new SurfaceMeta(new SurfaceOp.SurfaceAppend(), List.of()), false));
            commits.commitInCurrentTransaction(
                    new SessionCommit(session, 0, header, seed, List.of(), List.of(), List.of(), null, null));
            return new Reply(201, requireInTransaction(id));
        });
    }

    public AcceptedInput send(String id, String key, JsonNode body) {
        object(body, Set.of("content", "target"));
        JsonNode content = body.get("content");
        if (content == null || !content.isArray() || content.isEmpty() || content.size() > 32)
            throw invalid();
        int total = 0;
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode block : content) {
            object(block, Set.of("type", "text"));
            if (!"text".equals(text(block, "type", true, 16)))
                throw invalid();
            String value = text(block, "text", true, 262144);
            total += value.length();
            if (total > 262144)
                throw invalid();
            blocks.add(new TextBlock(value));
        }
        InboxTarget target = body.hasNonNull("target") ? InboxTarget.valueOf(text(body, "target", true, 16))
                : InboxTarget.NEXT_TURN;
        String scope = "POST:/conversations/" + id + "/messages";
        SessionId session = SessionId.of(get(id).sessionId());
        ChatAgentService.Reservation reservation;
        try {
            reservation = agents.reserve(session);
        } catch (ApiFailure unavailable) {
            // Completed idempotent responses still replay when new admission is full.
            return new AcceptedInput(idempotency.execute(scope, key, body, () -> {
                throw unavailable;
            }), null);
        }
        try {
            Result result = idempotency.execute(scope, key, body, () -> {
                ConversationView conversation = requireInTransaction(id);
                active(conversation);
                String inputId = ids.newId("input");
                inbox.enqueueInCurrentTransaction(new PendingInput(new InputId(inputId), session, target,
                        new InboxItem(inputId, blocks, Map.of()), clock.now()));
                return new Reply(202, Map.of("inputId", inputId));
            });
            return new AcceptedInput(result, reservation);
        } catch (RuntimeException error) {
            agents.releaseReservation(reservation);
            throw error;
        }
    }

    public void activate(String id, ChatAgentService.Reservation reservation) {
        try {
            ConversationView conversation = get(id);
            SessionId session = SessionId.of(conversation.sessionId());
            if ("ACTIVE".equals(conversation.status()) && !inbox.listPending(session).isEmpty()) {
                synchronized (agents) {
                    // The pending lookup can race archive. Recheck under the same
                    // boundary as archive's commit/cancel before creating a writer.
                    ConversationView current = get(id);
                    if ("ACTIVE".equals(current.status()))
                        agents.wake(session, current.assistantId());
                }
            }
        } finally {
            // A delayed completion may release only its own admission, never a newer
            // input's.
            agents.releaseReservation(reservation);
        }
    }

    public record AcceptedInput(Result response, ChatAgentService.Reservation reservation) {
    }

    public Result patch(String id, String key, JsonNode body) {
        object(body, Set.of("title", "metadataVersion", "toolPermissionMode", "approvalPolicy", "toolRules"));
        JsonNode version = body.get("metadataVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0)
            throw invalid();
        if (!body.has("title") && !body.has("toolPermissionMode") && !body.has("approvalPolicy")
                && !body.has("toolRules"))
            throw invalid();
        String title = body.has("title") ? text(body, "title", true, 200) : null;
        return idempotency.execute("PATCH:/conversations/" + id, key, body, () -> {
            ConversationView old = requireInTransaction(id);
            active(old);
            String mode = body.has("toolPermissionMode") ? policyMode(body.get("toolPermissionMode"))
                    : old.toolPermissionMode();
            String approval = body.has("approvalPolicy") ? policyApproval(body.get("approvalPolicy"))
                    : old.approvalPolicy();
            List<ToolRule> rules = body.has("toolRules") ? policyRules(body.get("toolRules")) : old.toolRules();
            String rulesJson = rules == null ? null : codec.encode(rules, new TypeReference<List<ToolRule>>() {
            });
            int changed = jdbc.sql(
                    "UPDATE conversations SET title=:title,tool_permission_mode=:mode,approval_policy=:approval,tool_rules_json=:rules,metadata_version=metadata_version+1,updated_at=:at WHERE id=:id AND metadata_version=:version AND status='ACTIVE'")
                    .param("title", title == null ? old.title() : title).param("mode", mode).param("approval", approval)
                    .param("rules", rulesJson).param("at", EventCodec.timestamp(clock.now())).param("id", id)
                    .param("version", version.asLong()).update();
            if (changed != 1)
                throw new ConcurrentModificationException("conversation metadata changed");
            return new Reply(200, requireInTransaction(id));
        });
    }

    private static String policyMode(JsonNode value) {
        if (value == null || value.isNull())
            return null;
        if (!value.isString() || !Set.of("READ_ONLY", "WORKSPACE_WRITE", "FULL_ACCESS").contains(value.stringValue()))
            throw invalid();
        return value.stringValue();
    }

    private static String policyApproval(JsonNode value) {
        if (value == null || value.isNull())
            return null;
        if (!value.isString() || !Set.of("ASK", "NEVER").contains(value.stringValue()))
            throw invalid();
        return value.stringValue();
    }

    private static List<ToolRule> policyRules(JsonNode value) {
        if (value == null || value.isNull())
            return null;
        if (!value.isArray() || value.size() > 64)
            throw invalid();
        List<ToolRule> rules = new ArrayList<>();
        for (JsonNode rule : value) {
            if (rule == null || !rule.isObject())
                throw invalid();
            for (String key : rule.propertyNames())
                if (!Set.of("tool", "decision", "commandRegex", "pathGlob").contains(key))
                    throw invalid();
            String tool = ruleText(rule, "tool"), decision = ruleText(rule, "decision");
            String commandRegex = rule.get("commandRegex") == null || rule.get("commandRegex").isNull()
                    ? null
                    : ruleText(rule, "commandRegex");
            String pathGlob = rule.get("pathGlob") == null || rule.get("pathGlob").isNull()
                    ? null
                    : ruleText(rule, "pathGlob");
            try {
                rules.add(new ToolRule(tool, pathGlob, commandRegex, decision));
            } catch (IllegalArgumentException unsafe) {
                throw invalid();
            }
        }
        return List.copyOf(rules);
    }

    private static String ruleText(JsonNode rule, String field) {
        JsonNode value = rule.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > 256)
            throw invalid();
        return value.stringValue();
    }

    public Result archive(String id, String key) {
        Result result;
        SessionId session;
        synchronized (agents) {
            result = idempotency.execute("DELETE:/conversations/" + id, key, Map.of("id", id), () -> {
                requireInTransaction(id);
                jdbc.sql(
                        "UPDATE conversations SET status='ARCHIVED',metadata_version=metadata_version+1,archived_at=:at,updated_at=:at WHERE id=:id AND status='ACTIVE'")
                        .param("at", EventCodec.timestamp(clock.now())).param("id", id).update();
                return new Reply(204, null);
            });
            session = SessionId.of(get(id).sessionId());
            if (!result.replayed())
                agents.cancel(session);
        }
        // Drain outside the lifecycle monitor and all database transactions.
        // Retrying an archived response also finishes any interrupted retirement.
        agents.retire(session);
        return result;
    }

    public Result cancel(String id, int turn, String key) {
        if (turn < 1)
            throw invalid();
        Result result = idempotency.execute("POST:/conversations/" + id + "/turns/" + turn + "/cancel", key,
                Map.of("turn", turn), () -> {
                    ConversationView conversation = requireInTransaction(id);
                    var running = jdbc.sql("SELECT current_turn,open_turn FROM agent_sessions WHERE id=:id")
                            .param("id", conversation.sessionId())
                            .query((rs, n) -> new long[] { rs.getLong(1), rs.getLong(2) }).single();
                    if (running[0] != turn || running[1] != 1)
                        throw new ApiFailure(409, "TURN_NOT_RUNNING", "The requested turn is not running.");
                    return new Reply(202, Map.of("turn", turn));
                });
        if (!result.replayed())
            agents.cancel(SessionId.of(get(id).sessionId()), CancelOptions.user().forTurn(turn));
        return result;
    }

    public ConversationView get(String id) {
        return transactions.read(() -> requireInTransaction(id));
    }

    public List<ConversationView> list() {
        return transactions.read(() -> jdbc.sql("SELECT * FROM conversations ORDER BY updated_at DESC,id LIMIT 200")
                .query((rs, n) -> view(rs)).list());
    }

    private ConversationView requireInTransaction(String id) {
        validId(id);
        return jdbc.sql("SELECT * FROM conversations WHERE id=:id").param("id", id)
                .query((rs, n) -> view(rs))
                .optional().orElseThrow(() -> new NoSuchElementException("conversation not found"));
    }

    private ConversationView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        String rules = rs.getString("tool_rules_json");
        return new ConversationView(rs.getString("id"), rs.getString("title"), rs.getString("assistant_id"),
                rs.getString("session_id"), rs.getString("status"), rs.getLong("last_seq"),
                rs.getLong("metadata_version"), rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("tool_permission_mode"), rs.getString("approval_policy"),
                rules == null ? null : codec.decode(rules, new TypeReference<List<ToolRule>>() {
                }));
    }

    public List<MessageView> messages(String id) {
        return transactions.read(() -> {
            requireInTransaction(id);
            return jdbc.sql(
                    "SELECT n.id,n.anchor_seq,n.source_seq,n.role,n.status,m.content_json,m.usage_json,m.tool_call_id,m.updated_at FROM message_nodes n JOIN messages m ON m.node_id=n.id WHERE n.conversation_id=:id AND (n.visible=1 OR n.status='STREAMING') ORDER BY n.surface_position,n.source_seq LIMIT 1000")
                    .param("id", id)
                    .query((rs, n) -> new MessageView(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                            rs.getString(5),
                            codec.decode(rs.getString(6), new TypeReference<List<ContentBlock>>() {
                            }), rs.getString(7) == null ? null : codec.decode(rs.getString(7), TokenUsage.class),
                            rs.getString(8), rs.getString(9)))
                    .list();
        });
    }

    public EventPage events(SessionId id, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 256)
            throw invalid();
        return transactions.read(() -> {
            long last = jdbc.sql("SELECT last_seq FROM agent_sessions WHERE id=:id").param("id", id.value())
                    .query(Long.class).optional().orElseThrow(() -> new NoSuchElementException("session not found"));
            if (after > last)
                throw invalid();
            List<SessionEvent> events = jdbc
                    .sql("SELECT * FROM session_events WHERE session_id=:id AND seq>:after ORDER BY seq LIMIT :limit")
                    .param("id", id.value()).param("after", after).param("limit", limit)
                    .query((rs, n) -> codec.readEvent(rs)).list();
            return new EventPage(id.value(), after, last, events,
                    events.isEmpty() ? after : events.get(events.size() - 1).seq());
        });
    }

    private static void active(ConversationView view) {
        if (!"ACTIVE".equals(view.status()))
            throw new ApiFailure(409, "CONVERSATION_ARCHIVED", "The conversation is archived.");
    }

    private static void validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,128}"))
            throw invalid();
    }

    private static void object(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject())
            throw invalid();
        body.propertyNames().forEach(k -> {
            if (!allowed.contains(k))
                throw invalid();
        });
    }

    private static String text(JsonNode body, String key, boolean required, int max) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) {
            if (required)
                throw invalid();
            return null;
        }
        if (!value.isString() || value.asString().length() > max || (required && value.asString().isBlank()))
            throw invalid();
        return value.asString();
    }

    private static ApiFailure invalid() {
        return new ApiFailure(400, "INVALID_REQUEST", "The request is invalid.");
    }

    public record ConversationView(String id, String title, String assistantId, String sessionId, String status,
            long lastSeq, long metadataVersion, String createdAt, String updatedAt, String toolPermissionMode,
            String approvalPolicy, List<ToolRule> toolRules) {
    }

    public record MessageView(String id, long anchorSeq, long sourceSeq, String role, String status,
            List<ContentBlock> content, TokenUsage usage, String toolCallId, String updatedAt) {
    }

    public record EventPage(String sessionId, long after, long lastSeq, List<SessionEvent> events, long nextSeq) {
    }
}
