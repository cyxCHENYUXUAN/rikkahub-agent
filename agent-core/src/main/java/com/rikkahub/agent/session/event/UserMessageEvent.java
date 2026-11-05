package com.rikkahub.agent.session.event;

import com.rikkahub.agent.message.ContentBlock;

import java.util.List;
import java.util.Objects;

public record UserMessageEvent(String inputId, List<ContentBlock> content, String source)
        implements SessionEventPayload {
    public UserMessageEvent {
        Objects.requireNonNull(inputId, "inputId");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(source, "source");
    }
}
