package com.rikkahub.agent.config;

/** How a changed system prompt is reflected on the session surface. */
public enum SystemPromptUpdatePolicy {
    /** Replace the previous system message in place via a surface replace. */
    REPLACE_IN_PLACE,
    /** Keep the old system message and append the new version. */
    APPEND
}
