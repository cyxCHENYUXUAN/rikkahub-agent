package com.rikkahub.agent.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Describes the process and initial directory of an interactive terminal. */
public record TerminalSpec(Path cwd, List<String> argv, Map<String, String> environment) {
    public TerminalSpec {
        Objects.requireNonNull(cwd, "cwd");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }
}
