package com.rikkahub.agent.model;

import java.util.Objects;

/** Identifies a provider together with the concrete model to invoke. */
public record ModelRef(String provider, String model) {
    public ModelRef {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }
}
