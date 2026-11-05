package com.rikkahub.agent.scope;

import java.util.Objects;

/**
 * Typed service key. Identity is the name: two keys with the same name are the
 * same service, and the generic parameter is not part of equality.
 */
public record ServiceKey<T>(String name) {
    public ServiceKey {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("service key name must not be blank");
        }
    }

    public static <T> ServiceKey<T> of(String name) {
        return new ServiceKey<>(name);
    }
}
