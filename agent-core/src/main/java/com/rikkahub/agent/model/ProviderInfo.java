package com.rikkahub.agent.model;

import java.util.Objects;
import java.util.Set;

/** Static description of a model provider and the capabilities it supports. */
public record ProviderInfo(String id, String displayName, Set<ModelCapability> capabilities) {
    public ProviderInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
    }
}
