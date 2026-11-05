package com.rikkahub.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.tool.ToolSchema;

/**
 * A model call frozen at the step boundary. All collections are copied at
 * construction, including nested JSON metadata, so once this instance is handed to an {@link LlmAdapter} neither
 * the caller nor the adapter may re-assemble or mutate it; retries reuse the same
 * frozen request and only refresh runtime facts.
 */
public record LlmRequest(String requestId, ModelRef model, String systemPrompt,
                         List<Message> messages, List<ToolSchema> tools,
                         GenerationOptions generation, Map<String, Object> metadata,
                         Instant createdAt) {
    public LlmRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        Objects.requireNonNull(model, "model");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(generation, "generation");
        metadata = FrozenValues.map(metadata, "metadata");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
