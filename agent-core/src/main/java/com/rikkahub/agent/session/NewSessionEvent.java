package com.rikkahub.agent.session;

import java.util.Objects;

import com.rikkahub.agent.session.event.SessionEventPayload;

/** Event content whose sequence and timestamp are assigned by the store. */
public record NewSessionEvent(SessionEventPayload data, SurfaceMeta surface, boolean ignorable) {
    public NewSessionEvent {
        Objects.requireNonNull(data, "data");
    }
}
