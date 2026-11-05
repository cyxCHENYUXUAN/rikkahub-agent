package com.rikkahub.agent.approval;

public sealed interface ApprovalOutcome
        permits ApprovalOutcome.AllowedOnce, ApprovalOutcome.Rejected,
                ApprovalOutcome.Cancelled, ApprovalOutcome.Unavailable {

    /** Single-use approval. Permanent policies belong to configuration, not a decision. */
    record AllowedOnce() implements ApprovalOutcome {
    }

    record Rejected(String reason) implements ApprovalOutcome {
    }

    record Cancelled(String reason) implements ApprovalOutcome {
    }

    record Unavailable(String reason) implements ApprovalOutcome {
    }
}
