package com.rikkahub.agent.execution;

/** How much of the host a workspace-backed tool may touch. */
public enum SandboxPolicy {
    READ_ONLY,
    WORKSPACE_WRITE,
    FULL_ACCESS
}
