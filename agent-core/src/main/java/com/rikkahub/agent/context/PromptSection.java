package com.rikkahub.agent.context;

import java.util.Objects;

/** One ordered, named block of system prompt text. Content is untrusted data. */
public record PromptSection(String name, int order, String content) {
    public PromptSection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("invalid prompt section name");
        }
        if (order < 0) {
            throw new IllegalArgumentException("prompt section order must be non-negative");
        }
    }
}
