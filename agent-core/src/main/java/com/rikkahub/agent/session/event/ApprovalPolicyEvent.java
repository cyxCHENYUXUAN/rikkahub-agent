package com.rikkahub.agent.session.event;

import com.rikkahub.agent.approval.ApprovalPolicy;

import java.util.Objects;

/** Session-level approval policy override; the last event wins. */
public record ApprovalPolicyEvent(ApprovalPolicy policy, int policyVersion)
        implements SessionEventPayload {
    public ApprovalPolicyEvent {
        Objects.requireNonNull(policy, "policy");
    }
}
