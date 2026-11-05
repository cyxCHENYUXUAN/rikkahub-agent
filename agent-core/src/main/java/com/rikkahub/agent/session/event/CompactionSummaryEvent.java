package com.rikkahub.agent.session.event;

import com.rikkahub.agent.message.TokenUsage;

import java.util.List;
import java.util.Objects;

public record CompactionSummaryEvent(String compactionId, long shadowedStartSeq,
                                     long shadowedEndSeq, List<Long> shadowedSeqs,
                                     String summary, TokenUsage usage, String provider,
                                     String model) implements SessionEventPayload {
    public CompactionSummaryEvent {
        Objects.requireNonNull(compactionId, "compactionId");
        shadowedSeqs = List.copyOf(Objects.requireNonNull(shadowedSeqs, "shadowedSeqs"));
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
    }
}
