package com.rikkahub.agent.session.event;

import java.util.Objects;

public record ApprovalDecidedEvent(String approvalId, String decision, String note)
        implements SessionEventPayload {
    public ApprovalDecidedEvent {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(note, "note");
    }
}
