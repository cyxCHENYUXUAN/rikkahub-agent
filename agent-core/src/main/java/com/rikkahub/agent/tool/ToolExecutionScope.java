package com.rikkahub.agent.tool;

import java.util.Objects;

/** Visible tools and the runtime facts for one tool invocation. */
public record ToolExecutionScope(ToolScope tools, ToolRunContext context) {
    public ToolExecutionScope {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(context, "context");
    }
}
