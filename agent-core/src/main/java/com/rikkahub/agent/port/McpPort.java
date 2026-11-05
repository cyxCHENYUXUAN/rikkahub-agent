package com.rikkahub.agent.port;

import java.util.List;

/** One connected MCP server. Connection lifecycle is owned by the backend. */
public interface McpPort extends AutoCloseable {
    List<McpToolDescriptor> tools();

    String call(String tool, String argumentsJson) throws Exception;

    default String call(String tool,String argumentsJson,com.rikkahub.agent.api.CancellationToken signal) throws Exception {
        signal.throwIfCancelled(); return call(tool,argumentsJson);
    }

    @Override
    void close();
}
