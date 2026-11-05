package com.rikkahub.agent.session.event;

import java.util.Objects;

/**
 * A tool call as emitted by the model. {@code argumentsJson} is the raw,
 * unparsed argument string.
 */
public record ToolCallEvent(int turn, int step, String callId, String toolName,
                            String argumentsJson) implements SessionEventPayload {
    public ToolCallEvent {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
