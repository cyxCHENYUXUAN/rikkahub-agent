package com.rikkahub.agent.execution;

import java.util.Objects;

/** Complete means the observed process tree exited and both output pumps drained. */
public record ProcessOutput(CapturedOutput stdout, CapturedOutput stderr, boolean complete, String failure) {
    public ProcessOutput {
        Objects.requireNonNull(stdout, "stdout"); Objects.requireNonNull(stderr, "stderr");
    }
}
