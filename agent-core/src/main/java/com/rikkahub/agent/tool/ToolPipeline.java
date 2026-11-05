package com.rikkahub.agent.tool;

import com.rikkahub.agent.api.CancellationToken;

import java.time.Duration;

/** The single execution entry point for registered tools. */
@FunctionalInterface
public interface ToolPipeline {
    ToolResult execute(ToolCall call, ToolExecutionScope scope, CancellationToken signal);

    default java.util.List<ToolResult> executeBatch(java.util.List<ToolCall> calls, ToolExecutionScope scope, CancellationToken signal) {
        return calls.stream().map(call -> execute(call, scope, signal)).toList();
    }

    /**
     * Waits until all actual work has returned, including work that ignored an
     * earlier cancellation or timeout. Synchronous implementations need no drain.
     */
    default boolean awaitQuiescence(Duration timeout) {
        return true;
    }

    /** Waits for one session; implementations without isolation may drain globally. */
    default boolean awaitQuiescence(String sessionId, Duration timeout) {
        return awaitQuiescence(timeout);
    }
}
