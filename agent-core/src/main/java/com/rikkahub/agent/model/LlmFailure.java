package com.rikkahub.agent.model;

import java.util.Map;
import java.util.Objects;

/**
 * Structured model failure. {@code retryable} is advisory input to
 * {@link RetryPolicy}; {@code details} may be empty but never null.
 */
public record LlmFailure(String code, String message, boolean retryable,
                         Integer statusCode, Map<String, Object> details) {
    public LlmFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (code.isBlank()) {
            throw new IllegalArgumentException("failure code must not be blank");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
