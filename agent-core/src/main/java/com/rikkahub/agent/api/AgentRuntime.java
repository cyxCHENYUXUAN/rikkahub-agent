package com.rikkahub.agent.api;

import java.util.List;
import java.util.Optional;

import com.rikkahub.agent.session.SessionId;

/** Creates and tracks the agents hosted by one runtime. */
public interface AgentRuntime {
    AgentHandle create(AgentSpec spec);

    AgentHandle resume(SessionId sessionId, AgentOptions options);

    Optional<Agent> find(AgentId id);

    List<Agent> list();
}
