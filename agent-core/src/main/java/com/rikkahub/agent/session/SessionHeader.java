package com.rikkahub.agent.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable session identity and product-facing tags. {@code parentSessionId},
 * {@code origin} and {@code agentPreset} are nullable.
 */
public record SessionHeader(SessionId id, SessionId parentSessionId, Path cwd, int formatVersion,
                            String origin, String agentPreset, Map<String, String> metadata,
                            Instant createdAt) {
    public SessionHeader {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Map.copyOf(metadata);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static SessionHeader newSession(SessionId id, Path cwd, Instant at) {
        return new SessionHeader(id, null, cwd, SessionFormat.CURRENT, "new", null, Map.of(), at);
    }
}
