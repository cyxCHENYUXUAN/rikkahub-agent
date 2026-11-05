package com.rikkahub.agent.session.event;

import java.util.Objects;

public record CompactionStartEvent(String compactionId, int trigger)
        implements SessionEventPayload {
    public CompactionStartEvent {
        Objects.requireNonNull(compactionId, "compactionId");
    }
}
