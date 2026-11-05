package com.rikkahub.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.rikkahub.agent.message.ContentBlock;

/** A runtime input queued for an agent. */
public record InboxItem(String id, List<ContentBlock> content, Map<String, Object> metadata) {
    public InboxItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("inbox item id must not be null or blank");
        }
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
