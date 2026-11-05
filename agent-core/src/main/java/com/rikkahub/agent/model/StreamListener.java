package com.rikkahub.agent.model;

import com.rikkahub.agent.message.StreamChunk;

/**
 * Receives a normalized stream. The terminal contract is fail-closed: exactly one
 * of {@link #onComplete()} or {@link #onFailure(LlmFailure)} must be delivered,
 * never both, and never neither. A failure must be reported through
 * {@code onFailure} rather than silently completing an empty response.
 */
public interface StreamListener {
    void onChunk(StreamChunk chunk);

    void onFailure(LlmFailure failure);

    void onComplete();
}
