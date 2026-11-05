package com.rikkahub.agent.session;

import java.time.Instant;
import java.util.Objects;

public record SessionSummary(SessionId sessionId, SessionStatus status, long lastSeq,
                             Instant updatedAt) {
    public SessionSummary {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
