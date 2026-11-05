package com.rikkahub.agent.session.event;

import java.util.Objects;

/**
 * Compact record of one streamed fragment. Only {@code kind} is required; the
 * remaining fields depend on the kind of evidence being summarized.
 */
public record StreamEvidence(String kind, String text, Integer index, String callId,
                             String toolName) {
    public StreamEvidence {
        Objects.requireNonNull(kind, "kind");
    }
}
