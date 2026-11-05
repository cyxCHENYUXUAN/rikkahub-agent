package com.rikkahub.agent.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * Completed process output. The exit code is platform-defined and may be
 * negative on hosts where the native status is unsigned.
 */
public record ProcessResult(int exitCode, String stdout, String stderr, Duration duration, ProcessOutput output) {
    /** Backwards-compatible constructor for existing port implementers. */
    public ProcessResult(int exitCode, String stdout, String stderr, Duration duration) {
        this(exitCode, stdout, stderr, duration, new ProcessOutput(
                new CapturedOutput(stdout, stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, false, null, false),
                new CapturedOutput(stderr, stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, false, null, false), true, null));
    }

    public ProcessResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }
}
