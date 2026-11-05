package com.rikkahub.agent.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sampling parameters frozen into an {@link LlmRequest}. {@code extra} carries
 * provider-specific knobs as immutable JSON values. Nested maps, lists, sets,
 * arrays and JSON trees are snapshotted; opaque objects and cycles are rejected.
 */
public record GenerationOptions(double temperature, double topP, int maxOutputTokens,
                                List<String> stopSequences, Map<String, Object> extra) {
    public GenerationOptions {
        if (!(temperature >= 0.0 && temperature <= 2.0)) {
            throw new IllegalArgumentException("temperature must be within [0, 2]");
        }
        if (!(topP > 0.0 && topP <= 1.0)) {
            throw new IllegalArgumentException("topP must be within (0, 1]");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        stopSequences = List.copyOf(Objects.requireNonNull(stopSequences, "stopSequences"));
        extra = FrozenValues.map(extra, "extra");
    }

    public static GenerationOptions defaults() {
        return new GenerationOptions(0.7, 1.0, 4096, List.of(), Map.of());
    }
}
