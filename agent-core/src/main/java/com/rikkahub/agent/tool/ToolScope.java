package com.rikkahub.agent.tool;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Selects which registered tools are visible to a caller. An empty
 * {@code enabledTools} set means every registered tool is enabled.
 */
public record ToolScope(String sessionId, String agentId, Path cwd,
                        Set<String> enabledTools) {
    public ToolScope {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(cwd, "cwd");
        enabledTools = Set.copyOf(Objects.requireNonNull(enabledTools, "enabledTools"));
    }
}
