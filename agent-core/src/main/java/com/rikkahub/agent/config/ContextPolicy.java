package com.rikkahub.agent.config;

import java.util.Objects;

public record ContextPolicy(int contextWindowTokens, int compactionThresholdTokens,
                            int keepRecentTokens, SystemPromptUpdatePolicy systemPromptUpdate) {
    public ContextPolicy {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        if (compactionThresholdTokens <= 0) {
            throw new IllegalArgumentException("compactionThresholdTokens must be positive");
        }
        if (keepRecentTokens <= 0) {
            throw new IllegalArgumentException("keepRecentTokens must be positive");
        }
        if (compactionThresholdTokens > contextWindowTokens) {
            throw new IllegalArgumentException(
                    "compactionThresholdTokens must not exceed contextWindowTokens");
        }
        Objects.requireNonNull(systemPromptUpdate, "systemPromptUpdate");
    }

    /** Threshold is 80% and keep-recent is 20% of the window, rounded down. */
    public static ContextPolicy defaults(int contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        int threshold = (int) Math.max(1L, (long) contextWindowTokens * 80 / 100);
        int keepRecent = (int) Math.max(1L, (long) contextWindowTokens * 20 / 100);
        return new ContextPolicy(contextWindowTokens, threshold, keepRecent,
                SystemPromptUpdatePolicy.REPLACE_IN_PLACE);
    }
}
