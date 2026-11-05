package com.rikkahub.agent.event;

/** Process-local consumer of real-time agent events. */
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
