package com.rikkahub.agent.session.event;

import java.util.Objects;

/** Log-only envelope of the next model request, including frozen config and tools. */
public record RequestHeaderEvent(String requestJson) implements SessionEventPayload {
    public RequestHeaderEvent {
        Objects.requireNonNull(requestJson, "requestJson");
    }
}
