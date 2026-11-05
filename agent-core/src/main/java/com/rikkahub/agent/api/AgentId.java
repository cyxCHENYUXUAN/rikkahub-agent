package com.rikkahub.agent.api;

/** Stable identity of a running agent. */
public record AgentId(String value) {
    public AgentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("agent id must not be null or blank");
        }
    }
}
