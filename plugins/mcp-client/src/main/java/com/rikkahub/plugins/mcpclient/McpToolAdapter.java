package com.rikkahub.plugins.mcpclient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.rikkahub.agent.port.McpPort;
import com.rikkahub.agent.port.McpToolDescriptor;
import com.rikkahub.agent.tool.ToolDefinition;
import com.rikkahub.agent.tool.ToolResult;
import com.rikkahub.agent.tool.ToolSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Maps one connected MCP server into namespaced core tools. */
public final class McpToolAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private McpToolAdapter() {
    }

    public static List<ToolDefinition> definitions(McpPort port) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (McpToolDescriptor descriptor : port.tools()) {
            String name = McpNames.tool(descriptor.server(), descriptor.tool());
            definitions.add(new ToolDefinition() {
                public ToolSchema schema() {
                    JsonNode parameters;
                    try {
                        parameters = JSON.readTree(descriptor.schemaJson());
                    } catch (RuntimeException invalid) {
                        parameters = JSON.createObjectNode();
                    }
                    return new ToolSchema(name, descriptor.description(), parameters);
                }

                public boolean concurrencySafe(JsonNode args) { return false; }

                public boolean idempotent() { return false; }

                public Duration timeout() { return Duration.ofSeconds(60); }

                public ToolResult execute(JsonNode args, com.rikkahub.agent.tool.ToolRunContext context) {
                    try {
                        JsonNode response=JSON.readTree(port.call(descriptor.tool(), JSON.writeValueAsString(args), context.signal()));
                        return new ToolResult(List.of(new com.rikkahub.agent.message.TextBlock(JSON.writeValueAsString(response))),
                                response.path("isError").asBoolean(false),java.util.Map.of());
                    } catch (RuntimeException failure) {
                        return ToolResult.error("mcp_call_failed");
                    } catch (Exception failure) {
                        return ToolResult.error("mcp_call_failed");
                    }
                }
            });
        }
        return List.copyOf(definitions);
    }
}
