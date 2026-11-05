package com.rikkahub.agent.tool;

/** Observes or rewrites a tool result before content finalization. */
public interface ToolPostExecute {
    ToolResult afterExecute(ToolExecution execution, ToolResult result, ToolRunContext context);
}
