package com.rikkahub.agent.port;

import java.util.Objects;

/** Search result with a bounded snippet, never the whole document. */
public record MemoryHit(String id, String title, String snippet, double score) {
    public MemoryHit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(snippet, "snippet");
    }
}
