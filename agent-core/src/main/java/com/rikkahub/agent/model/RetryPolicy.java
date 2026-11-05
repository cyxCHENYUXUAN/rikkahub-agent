package com.rikkahub.agent.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded exponential backoff for model streams. Attempts are 1-based; retries
 * only refresh runtime facts and never trigger re-assembly of the request.
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
                          Duration maxDelay) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (!(multiplier > 0.0) || Double.isInfinite(multiplier) || Double.isNaN(multiplier)) {
            throw new IllegalArgumentException("multiplier must be a positive finite number");
        }
        maxDelay = Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay must not be negative");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), 2.0, Duration.ofSeconds(10));
    }

    /** Exponential backoff for a 1-based attempt, capped at {@link #maxDelay}. */
    public Duration delayFor(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        double scaled = initialDelay.toNanos() * Math.pow(multiplier, attempt - 1);
        long maxNanos = maxDelay.toNanos();
        long nanos = scaled >= (double) maxNanos ? maxNanos : (long) scaled;
        return Duration.ofNanos(nanos);
    }

    public boolean shouldRetry(LlmFailure failure, int attempt) {
        return failure != null && failure.retryable() && attempt < maxAttempts;
    }
}
