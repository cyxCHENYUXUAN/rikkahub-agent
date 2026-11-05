package com.rikkahub.features.approvals.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.idempotency.IdempotencyService;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.idempotency.IdempotencyService.Reply;
import com.rikkahub.platform.idempotency.IdempotencyService.Result;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * HTTP-side approval decisions. The CAS stores the requested decision only;
 * the approval status is settled exclusively by the writer's ApprovalDecidedEvent.
 */
@Service
public final class ApprovalApplicationService {
    private final JdbcClient jdbc;
    private final IdempotencyService idempotency;
    private final DurableApprovalBroker broker;
    private final Clock clock;

    public ApprovalApplicationService(JdbcClient jdbc, IdempotencyService idempotency,
                                      DurableApprovalBroker broker, Clock clock) {
        this.jdbc = jdbc; this.idempotency = idempotency;
        this.broker = broker; this.clock = clock;
    }

    public List<ApprovalView> list(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiFailure(400, "INVALID_REQUEST", "The sessionId is invalid.");
        }
        requireSession(sessionId);
        return jdbc.sql("""
                SELECT * FROM approvals WHERE session_id=:id ORDER BY asked_seq DESC LIMIT 100
                """).param("id", sessionId).query((rs, n) -> view(rs)).list();
    }

    private void requireSession(String sessionId) {
        Integer found = jdbc.sql("SELECT 1 FROM agent_sessions WHERE id=:id")
                .param("id", sessionId).query(Integer.class).optional().orElse(null);
        if (found == null) throw new ApiFailure(404, "SESSION_NOT_FOUND", "The session was not found.");
    }

    public Result decide(String id, String key, JsonNode body) {
        if (id == null || id.isBlank()) throw new ApiFailure(404, "APPROVAL_NOT_FOUND", "The approval was not found.");
        if (body == null || !body.isObject()
                || body.propertyNames().stream().anyMatch(name -> !"decision".equals(name))) {
            throw new ApiFailure(400, "INVALID_REQUEST", "The decision body accepts only the decision field.");
        }
        JsonNode value = body.get("decision");
        if (value == null || !value.isString()) {
            throw new ApiFailure(400, "INVALID_REQUEST", "The decision must be a string.");
        }
        String decision = value.stringValue();
        if (!Set.of("allowed-once", "rejected").contains(decision)) {
            throw new ApiFailure(400, "INVALID_REQUEST", "The decision must be allowed-once or rejected.");
        }
        String approvalId = id;
        Result result = idempotency.execute("POST:/approvals/" + approvalId, key, body, () -> {
            requireApproval(approvalId);
            Instant now = clock.now();
            Integer changed = jdbc.sql("""
                    UPDATE approvals SET requested_decision=:d, updated_at=:at
                    WHERE id=:id AND status='PENDING' AND expires_at IS NOT NULL AND expires_at>:now
                          AND (requested_decision IS NULL OR requested_decision=:d)
                    """).param("d", decision).param("at", EventCodec.timestamp(now))
                    .param("id", approvalId).param("now", EventCodec.timestamp(now)).update();
            if (changed != 1) throw conflict(approvalId);
            return new Reply(202, view(jdbc, approvalId));
        });
        // Wake the writer only after the CAS transaction has committed. A crash
        // before this line leaves a stale requested_decision, which restart
        // recovery and the broker treat as unauthorized.
        if (!result.replayed()) broker.recordDecision(approvalId, decision);
        return result;
    }

    private void requireApproval(String id) {
        String status = jdbc.sql("SELECT status FROM approvals WHERE id=:id").param("id", id)
                .query(String.class).optional().orElse(null);
        if (status == null) throw new ApiFailure(404, "APPROVAL_NOT_FOUND", "The approval was not found.");
    }

    private static ApiFailure conflict(String id) {
        return new ApiFailure(409, "APPROVAL_CONFLICT",
                "The approval " + id + " is no longer open for a " + "decision.");
    }

    private ApprovalView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApprovalView(rs.getString("id"), rs.getString("session_id"), rs.getString("call_id"),
                rs.getString("tool_name"), rs.getString("reason"), rs.getInt("turn"),
                rs.getString("arguments_json"), rs.getString("arguments_digest"), rs.getString("status"),
                rs.getString("requested_decision"), rs.getString("decision_note"),
                rs.getString("expires_at"), rs.getLong("asked_seq"),
                rs.getObject("decided_seq") == null ? null : rs.getLong("decided_seq"));
    }

    private static ApprovalView view(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT * FROM approvals WHERE id=:id").param("id", id)
                .query((rs, n) -> {
                    try { return new ApprovalView(rs.getString("id"), rs.getString("session_id"),
                            rs.getString("call_id"), rs.getString("tool_name"), rs.getString("reason"),
                            rs.getInt("turn"), rs.getString("arguments_json"), rs.getString("arguments_digest"),
                            rs.getString("status"), rs.getString("requested_decision"), rs.getString("decision_note"),
                            rs.getString("expires_at"), rs.getLong("asked_seq"),
                            rs.getObject("decided_seq") == null ? null : rs.getLong("decided_seq")); }
                    catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
                }).single();
    }

    public record ApprovalView(String id, String sessionId, String callId, String toolName, String reason,
                               int turn, String argumentsJson, String argumentsDigest, String state,
                               String requestedDecision, String decisionNote, String expiresAt,
                               long askedSeq, Long decidedSeq) {}
}
