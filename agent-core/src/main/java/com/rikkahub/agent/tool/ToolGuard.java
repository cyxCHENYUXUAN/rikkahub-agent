package com.rikkahub.agent.tool;

import java.util.Optional;

/** Can only reject a call; it can never force an allow. */
public interface ToolGuard {
    /** Returns a rejection reason, or empty to let the call continue. */
    Optional<String> check(ToolCall call, ToolRunContext context);
}
