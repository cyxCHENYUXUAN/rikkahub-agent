package com.rikkahub.agent.api;

import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.scope.AgentScope;
import com.rikkahub.agent.session.SessionId;

/** Immutable view of the resources owned by one agent. */
public interface AgentContext {
    AgentId agentId();

    SessionId sessionId();

    AgentConfigSnapshot config();

    ExecutionEnvironment environment();

    AgentScope scope();

    CancellationToken cancellation();
}
