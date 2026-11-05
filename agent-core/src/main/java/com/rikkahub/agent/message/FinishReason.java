package com.rikkahub.agent.message;

public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    ERROR,
    CANCELLED
}
