package com.rikkahub.agent.session.event;

import java.util.Objects;

public record RequestContextEvent(String provider, String model, int contextWindow,
                                  String systemPromptUpdate, String purpose)
        implements SessionEventPayload {
    public RequestContextEvent {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPromptUpdate, "systemPromptUpdate");
        Objects.requireNonNull(purpose, "purpose");
    }
}
