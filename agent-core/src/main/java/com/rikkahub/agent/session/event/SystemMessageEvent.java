package com.rikkahub.agent.session.event;

import java.util.Objects;

public record SystemMessageEvent(String text) implements SessionEventPayload {
    public SystemMessageEvent {
        Objects.requireNonNull(text, "text");
    }
}
