package com.rikkahub.agent.api;

import java.util.Objects;

import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.execution.ExecutionEnvironment;

/** Options for resuming an existing session; adapters come from runtime services. */
public record AgentOptions(AgentConfigSnapshot config, ExecutionEnvironment environment,
                           CancellationToken parentSignal) {
    public AgentOptions {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(parentSignal, "parentSignal");
    }
}
