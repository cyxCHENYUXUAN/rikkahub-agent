package com.rikkahub.agent.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A single foreground process invocation. */
public record Command(List<String> argv, Path cwd, Map<String, String> environment,
                      Duration timeout) {
    public Command {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        Objects.requireNonNull(cwd, "cwd");
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(timeout, "timeout");
    }
}
