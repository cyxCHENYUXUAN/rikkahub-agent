package com.rikkahub.agent.session;

import java.time.Instant;
import java.util.Objects;

import com.rikkahub.agent.message.TokenUsage;

/** One billable model call attributed to a session, provider and purpose. */
public record UsageRecord(SessionId sessionId, String provider, String model, String purpose,
                          TokenUsage usage, Instant at) {
    public UsageRecord {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(at, "at");
    }
}
