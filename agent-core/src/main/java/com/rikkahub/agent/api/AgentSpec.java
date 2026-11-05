package com.rikkahub.agent.api;

import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.session.SessionHeader;

/** Everything needed to create a brand-new agent. */
public record AgentSpec(AgentId id, SessionHeader header, AgentConfigSnapshot config,
                        ExecutionEnvironment environment, List<InboxItem> initialInputs) {
    public AgentSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(environment, "environment");
        initialInputs = List.copyOf(Objects.requireNonNull(initialInputs, "initialInputs"));
    }

    /** Compatibility constructor for callers that enqueue inputs after creation. */
    public AgentSpec(AgentId id, SessionHeader header, AgentConfigSnapshot config,
                     ExecutionEnvironment environment) {
        this(id, header, config, environment, List.of());
    }
}
