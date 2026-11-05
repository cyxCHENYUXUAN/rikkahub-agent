package com.rikkahub.agent.tool;

import com.rikkahub.agent.message.ContentBlock;

import java.util.List;

/** Ordered extension points. A guard can add restrictions but cannot remove them. */
public interface ToolHooks {
    default ToolDecision preExecute(ToolCall call, ToolRunContext context) {
        return new ToolDecision.Allow();
    }

    default ToolDecision guard(ToolCall call, ToolRunContext context) {
        return new ToolDecision.Allow();
    }

    default void postExecute(ToolCall call, ToolResult result, ToolRunContext context) {}

    default List<ContentBlock> projectContent(ToolCall call, ToolResult result) {
        return result.content();
    }
}
