package com.rikkahub.agent.event;

/** Process-local outlet for real-time agent events. */
public interface AgentEventSink {
    void emit(AgentEvent event);
}
