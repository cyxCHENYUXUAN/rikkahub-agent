package com.rikkahub.agent.tool;

/** Where the tool result content is expected to live. */
public enum ToolOutput {
    /** Content is returned inline in the tool result. */
    INLINE,
    /** Content is spilled to storage and referenced by the tool result. */
    SPILL
}
