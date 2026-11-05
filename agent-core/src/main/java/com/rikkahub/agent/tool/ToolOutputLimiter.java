package com.rikkahub.agent.tool;

/** Host-owned bounded output storage; returned content, including references, is durable. */
@FunctionalInterface
public interface ToolOutputLimiter {
    ToolResult limit(String sessionId, ToolCall call, ToolResult result);
}
