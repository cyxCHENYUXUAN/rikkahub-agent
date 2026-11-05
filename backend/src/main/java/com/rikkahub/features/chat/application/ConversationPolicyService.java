package com.rikkahub.features.chat.application;

import java.util.List;

import com.rikkahub.agent.config.ToolRule;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.features.assistants.application.AssistantApplicationService;
import com.rikkahub.features.assistants.application.AssistantApplicationService.AssistantProfile;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.TransactionRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * Resolves the effective tool/approval policy at turn start: conversation
 * override, then assistant, then safe product defaults. Null conversation
 * columns mean "inherit the assistant".
 */
@Service
public final class ConversationPolicyService {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final AssistantApplicationService assistants;

    public ConversationPolicyService(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec,
                                     AssistantApplicationService assistants) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = codec;
        this.assistants = assistants;
    }

    public Effective resolve(SessionId session) {
        return transactions.read(() -> {
            Row row = jdbc.sql("""
                    SELECT assistant_id, tool_permission_mode, approval_policy, tool_rules_json
                    FROM conversations WHERE session_id=:session
                    """).param("session", session.value())
                    .query((result, number) -> new Row(result.getString(1), result.getString(2),
                            result.getString(3), result.getString(4)))
                    .optional()
                    .orElseThrow(() -> new ApiFailure(404, "CONVERSATION_NOT_FOUND",
                            "The conversation was not found."));
            AssistantProfile assistant = assistants.requireInTransaction(row.assistantId());
            List<ToolRule> override = row.rulesJson() == null ? null
                    : codec.decode(row.rulesJson(), new TypeReference<List<ToolRule>>() { });
            return new Effective(assistant,
                    row.mode() != null ? row.mode() : assistant.toolPermissionMode(),
                    row.approval() != null ? row.approval() : assistant.approvalPolicy(),
                    override != null ? override : assistant.toolRules());
        });
    }

    public record Effective(AssistantProfile assistant, String mode, String approval, List<ToolRule> rules) {
        public Effective {
            rules = List.copyOf(rules);
        }
    }

    private record Row(String assistantId, String mode, String approval, String rulesJson) {}
}
