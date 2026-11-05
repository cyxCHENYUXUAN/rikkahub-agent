package com.rikkahub.agent.session.event;

import com.rikkahub.agent.message.ContentBlock;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable tool outcome; metadata carries host-owned references such as spilled output. */
public record ToolResultEvent(String callId, String toolName, List<ContentBlock> content,
                              boolean isError, Map<String, Object> metadata) implements SessionEventPayload {
    public ToolResultEvent {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Backward-compatible constructor for stored events/logs without metadata. */
    public ToolResultEvent(String callId, String toolName, List<ContentBlock> content, boolean isError) {
        this(callId, toolName, content, isError, Map.of());
    }
}
