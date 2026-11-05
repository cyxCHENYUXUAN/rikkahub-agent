package com.rikkahub.agent.session.event;

/** Marks the boundary between inherited history and live work after resume or fork. */
public record SessionEndSeedEvent(boolean inherited, long inheritedEventCount)
        implements SessionEventPayload {
}
