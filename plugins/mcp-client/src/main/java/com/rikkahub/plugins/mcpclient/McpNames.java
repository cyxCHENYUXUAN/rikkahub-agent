package com.rikkahub.plugins.mcpclient;

import java.util.regex.Pattern;

/** Stable MCP tool namespace; names are validated before exposure. */
public final class McpNames {
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]{1,48}");

    private McpNames() {
    }

    public static String tool(String server, String tool) {
        if (server == null || !PART.matcher(server).matches()
                || tool == null || !PART.matcher(tool).matches()) {
            throw new IllegalArgumentException("invalid MCP server or tool name");
        }
        return "mcp__" + server + "__" + tool;
    }
}
