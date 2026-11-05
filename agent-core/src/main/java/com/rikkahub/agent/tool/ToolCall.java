package com.rikkahub.agent.tool;

import java.util.Objects;

/**
 * A model-emitted tool call. {@code argumentsJson} keeps the model's original
 * JSON text; only the executing tool parses it.
 */
public record ToolCall(String callId, String toolName, String argumentsJson) {
    public ToolCall {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
