package com.rikkahub.agent.model;

import java.util.Objects;

/** Catalog entry for one model exposed by a provider. */
public record ModelInfo(String id, String displayName, int contextWindow,
                        int maxOutputTokens, boolean supportsTools, boolean supportsReasoning) {
    public ModelInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (id.isBlank()) {
            throw new IllegalArgumentException("model id must not be blank");
        }
        if (contextWindow < 0) {
            throw new IllegalArgumentException("contextWindow must be non-negative");
        }
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must be non-negative");
        }
    }
}
