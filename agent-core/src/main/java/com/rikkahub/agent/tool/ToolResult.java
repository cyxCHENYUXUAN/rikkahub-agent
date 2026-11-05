package com.rikkahub.agent.tool;

import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.message.TextBlock;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Model-visible outcome of a tool call. Errors are ordinary results with
 * {@code isError=true} so the model can observe and correct them.
 */
public record ToolResult(List<ContentBlock> content, boolean isError,
                         Map<String, Object> metadata) {
    public ToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Creates a successful result containing a single text block. */
    public static ToolResult text(String text) {
        return new ToolResult(List.of(new TextBlock(Objects.requireNonNull(text, "text"))),
                false, Map.of());
    }

    /** Creates a failed result containing a single text block. */
    public static ToolResult error(String message) {
        return new ToolResult(List.of(new TextBlock(Objects.requireNonNull(message, "message"))),
                true, Map.of());
    }
}
