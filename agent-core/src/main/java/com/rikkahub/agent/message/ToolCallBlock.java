package com.rikkahub.agent.message;

import java.util.Objects;

/**
 * Raw tool-call arguments exactly as produced by the model. They are never
 * parsed here; only a tool executor may parse them.
 */
public record ToolCallBlock(String callId, String toolName, String argumentsJson)
        implements ContentBlock {
    public ToolCallBlock {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
