package com.rikkahub.agent.session;

import java.time.Instant;
import java.util.Objects;

/** Stored work to dispatch after the enclosing session commit succeeds. */
public record EffectIntent(String id, String kind, String payloadJson, Instant availableAt) {
    public EffectIntent {
        if (id == null || id.isBlank() || kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("effect id and kind must not be blank");
        }
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(availableAt, "availableAt");
    }
}
