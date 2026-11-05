package com.rikkahub.agent.message;

import java.util.List;
import java.util.Objects;

public record ToolResultBlock(String callId, String toolName, List<ContentBlock> content,
                              boolean error) implements ContentBlock {
    public ToolResultBlock {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }
}
