package com.rikkahub.agent.execution;

import java.util.Objects;

/** A background job request. */
public record JobSpec(String name, Command command) {
    public JobSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
    }
}
