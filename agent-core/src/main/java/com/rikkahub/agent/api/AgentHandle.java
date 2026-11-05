package com.rikkahub.agent.api;

import java.util.Objects;

/** Runtime handle pairing the agent with the action that disposes it. */
public record AgentHandle(Agent agent, AutoCloseable disposer) implements AutoCloseable {
    public AgentHandle {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(disposer, "disposer");
    }

    @Override
    public void close() throws Exception {
        disposer.close();
    }
}
