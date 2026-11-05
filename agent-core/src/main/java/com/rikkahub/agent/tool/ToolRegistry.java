package com.rikkahub.agent.tool;

import java.util.List;
import java.util.Optional;

/** Tool discovery and registration; execution belongs exclusively to ToolPipeline. */
public interface ToolRegistry {
    List<ToolSchema> schemas(ToolScope scope);

    Optional<ToolDefinition> find(String name, ToolScope scope);

    AutoCloseable register(ToolDefinition tool, ToolScope scope);
}
