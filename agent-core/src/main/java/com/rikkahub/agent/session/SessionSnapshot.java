package com.rikkahub.agent.session;

import java.time.Instant;
import java.util.Objects;

public record SessionSnapshot(Session session, SessionStatus status, Instant updatedAt) {
    public SessionSnapshot {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
