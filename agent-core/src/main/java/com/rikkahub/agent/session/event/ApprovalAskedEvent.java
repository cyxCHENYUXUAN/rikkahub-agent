package com.rikkahub.agent.session.event;

import java.util.Objects;

public record ApprovalAskedEvent(String approvalId, String callId, String toolName,
                                 String reason, int turn, String argumentsDigest, java.time.Instant expiresAt) implements SessionEventPayload {
    public ApprovalAskedEvent(String approvalId,String callId,String toolName,String reason){this(approvalId,callId,toolName,reason,0,null,null);}
    public ApprovalAskedEvent {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(reason, "reason");
    }
}
