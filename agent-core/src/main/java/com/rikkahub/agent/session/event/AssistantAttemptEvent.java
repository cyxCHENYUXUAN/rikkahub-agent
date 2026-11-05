package com.rikkahub.agent.session.event;

import com.rikkahub.agent.message.TokenUsage;

import java.util.Objects;

public record AssistantAttemptEvent(int attempt, String failureCode, String failureMessage,
                                    boolean retryable, TokenUsage usage)
        implements SessionEventPayload {
    public AssistantAttemptEvent {
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(failureMessage, "failureMessage");
        Objects.requireNonNull(usage, "usage");
    }
}
