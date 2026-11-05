package com.rikkahub.agent.tool;

/** Runs before guards and execution; may allow, deny, cancel or ask. */
public interface ToolPreExecute {
    PreExecuteDecision decide(ToolCall call, ToolRunContext context);
}
