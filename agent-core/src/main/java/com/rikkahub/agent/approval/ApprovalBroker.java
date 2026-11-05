package com.rikkahub.agent.approval;

import com.rikkahub.agent.api.CancellationToken;

/**
 * Host-side approval channel. Implementations must fail closed: timeout,
 * shutdown or an unavailable UI yields Cancelled/Unavailable, never AllowedOnce.
 */
public interface ApprovalBroker {
    ApprovalOutcome request(ApprovalRequest request, CancellationToken signal);
}
