package com.rikkahub.agent.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Timing facts for one executed tool call, used by post-processing hooks. */
public record ToolExecution(ToolCall call, Instant startedAt, Duration duration) {
    public ToolExecution {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(startedAt, "startedAt");
        duration = Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }
}
