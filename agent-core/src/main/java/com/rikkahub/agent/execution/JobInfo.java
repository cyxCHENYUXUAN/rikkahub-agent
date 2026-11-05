package com.rikkahub.agent.execution;

import java.util.Objects;

/** Snapshot of a managed background job. {@code exitCode} is null while running. */
public record JobInfo(String id, String name, String status, Integer exitCode) {
    public JobInfo {
        requireText(id, "id");
        requireText(name, "name");
        requireText(status, "status");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
