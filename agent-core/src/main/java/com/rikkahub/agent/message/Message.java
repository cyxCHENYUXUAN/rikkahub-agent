package com.rikkahub.agent.message;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record Message(String id, MessageRole role, List<ContentBlock> content,
                      Instant createdAt, MessageSource source) {
    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(source, "source");
    }

    public static Message system(String id, String text, Instant at) {
        return new Message(id, MessageRole.SYSTEM, List.of(new TextBlock(text)), at,
                MessageSource.SYSTEM);
    }

    public static Message user(String id, List<ContentBlock> content, Instant at) {
        return new Message(id, MessageRole.USER, content, at, MessageSource.USER);
    }

    public static Message assistant(String id, List<ContentBlock> content, Instant at) {
        return new Message(id, MessageRole.ASSISTANT, content, at, MessageSource.MODEL);
    }

    public static Message tool(String id, List<ContentBlock> content, Instant at) {
        return new Message(id, MessageRole.TOOL, content, at, MessageSource.TOOL);
    }

    /** Concatenates only visible text blocks; used for previews and token estimation. */
    public String text() {
        return content.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .collect(Collectors.joining("\n"));
    }
}
