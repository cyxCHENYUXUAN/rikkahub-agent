package com.rikkahub.agent.tool;

import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.message.ContentBlock;

import java.time.Duration;
import java.util.List;

/** A registered capability that the model may call. */
public interface ToolDefinition {
    ToolSchema schema();

    default ToolOutput output() {
        return ToolOutput.INLINE;
    }

    ToolResult execute(JsonNode arguments, ToolRunContext context);

    default Duration timeout() {
        return Duration.ofSeconds(120);
    }

    default boolean concurrencySafe(JsonNode arguments) {
        return false;
    }

    default boolean idempotent() {
        return false;
    }

    default List<ContentBlock> finalizeContent(ToolExecution execution, ToolResult result) {
        return result.content();
    }
}
