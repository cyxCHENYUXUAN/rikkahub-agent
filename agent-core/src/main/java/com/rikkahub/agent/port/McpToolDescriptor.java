package com.rikkahub.agent.port;

import java.util.Objects;

/** One tool discovered from an MCP server; the exposed name is namespaced. */
public record McpToolDescriptor(String server, String tool, String description, String schemaJson) {
    public McpToolDescriptor {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(schemaJson, "schemaJson");
    }

    public String namespacedName() {
        return "mcp__" + server + "__" + tool;
    }
}
