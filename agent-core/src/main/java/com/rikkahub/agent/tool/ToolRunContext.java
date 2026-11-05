package com.rikkahub.agent.tool;

import com.rikkahub.agent.api.CancellationToken;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Runtime facts handed to hooks, guards and the executing tool. */
public record ToolRunContext(String sessionId, String agentId, Path cwd,
                             CancellationToken signal, Map<String, Object> attributes) {
    public ToolRunContext {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(cwd, "cwd");
        signal = Objects.requireNonNull(signal, "signal");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
