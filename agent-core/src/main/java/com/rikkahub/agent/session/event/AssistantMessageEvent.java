package com.rikkahub.agent.session.event;

import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.message.TokenUsage;

import java.util.List;
import java.util.Objects;

public record AssistantMessageEvent(List<ContentBlock> content, TokenUsage usage,
                                    String provider, String model, String stopReason,
                                    List<StreamEvidence> streamEvidence)
        implements SessionEventPayload {
    public AssistantMessageEvent {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(stopReason, "stopReason");
        streamEvidence = List.copyOf(Objects.requireNonNull(streamEvidence, "streamEvidence"));
    }
}
