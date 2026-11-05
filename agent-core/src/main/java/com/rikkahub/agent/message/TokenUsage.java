package com.rikkahub.agent.message;

public record TokenUsage(int inputTokens, int outputTokens,
                         Integer cacheReadTokens, Integer cacheWriteTokens) {
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
    }

    public static TokenUsage zero() {
        return new TokenUsage(0, 0, null, null);
    }

    public static TokenUsage of(int inputTokens, int outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, null, null);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
