package com.rikkahub.agent.port;

import java.time.Instant;
import java.util.Objects;

/** One user-editable memory document. Body is untrusted data, not policy. */
public record MemoryDocument(String id, String title, String body, Instant updatedAt) {
    public MemoryDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid memory id");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("memory title must not be blank");
        }
    }
}
