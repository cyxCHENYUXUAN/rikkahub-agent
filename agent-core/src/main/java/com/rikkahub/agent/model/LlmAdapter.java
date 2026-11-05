package com.rikkahub.agent.model;

import java.util.List;

import com.rikkahub.agent.api.CancellationToken;

/**
 * Provider seam. Implementations must treat the request as frozen: the same
 * instance is reused across retries and must not be mutated or reassembled.
 */
public interface LlmAdapter {
    ProviderInfo provider();

    List<ModelInfo> models();

    LlmStream stream(LlmRequest request, CancellationToken signal);
}
