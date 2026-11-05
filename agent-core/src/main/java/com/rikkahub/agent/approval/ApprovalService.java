package com.rikkahub.agent.approval;

import java.util.Objects;

import com.rikkahub.agent.api.CancelledException;
import com.rikkahub.agent.api.CancellationToken;

/**
 * Applies the session approval policy before reaching the host broker.
 * This is the only place that decides whether a tool may ask at all.
 */
public final class ApprovalService {
    private final ApprovalBroker broker;

    public ApprovalService(ApprovalBroker broker) {
        this.broker = broker;
    }

    public ApprovalOutcome request(ApprovalRequest request, ApprovalPolicy policy,
                                   CancellationToken signal) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(signal, "signal");

        if (signal.isCancelled()) {
            return new ApprovalOutcome.Cancelled("cancelled");
        }
        if (policy == ApprovalPolicy.NEVER) {
            return new ApprovalOutcome.Rejected("policy=never");
        }
        if (broker == null) {
            return new ApprovalOutcome.Unavailable("no approval broker");
        }
        try {
            ApprovalOutcome outcome = broker.request(request, signal);
            return outcome == null ? new ApprovalOutcome.Unavailable("no decision") : outcome;
        } catch (CancelledException ex) {
            return new ApprovalOutcome.Cancelled("cancelled");
        } catch (RuntimeException ex) {
            return new ApprovalOutcome.Unavailable("broker failure: " + ex.getMessage());
        }
    }
}
