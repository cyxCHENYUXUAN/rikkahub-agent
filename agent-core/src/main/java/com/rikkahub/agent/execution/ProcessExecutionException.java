package com.rikkahub.agent.execution;

import java.util.Objects;

/**
 * A command was started and failed. Carries its bounded, possibly partial output,
 * including spill references. Never retry the command merely to recover output.
 * Consult {@link ProcessOutput#complete()} before claiming native/pump drain.
 * Failures before process creation may still use ordinary validation or availability exceptions.
 */
public class ProcessExecutionException extends RuntimeException {
    private final String reason;
    private final ProcessResult result;

    public ProcessExecutionException(String reason, ProcessResult result) {
        this(reason, result, null);
    }

    public ProcessExecutionException(String reason, ProcessResult result, Throwable cause) {
        super("process execution failed: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
        this.result = Objects.requireNonNull(result, "result");
    }

    public String reason() { return reason; }
    public ProcessResult result() { return result; }
}
