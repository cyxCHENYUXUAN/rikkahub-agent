package com.rikkahub.plugins.modelproviders;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import com.rikkahub.agent.model.LlmFailure;

/** Classifies errors without retaining provider bodies, URLs, headers or exception messages. */
final class RetryClassifier {
    private RetryClassifier() {
    }

    static LlmFailure http(int status) {
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        return new LlmFailure("http_" + status, "Model provider returned HTTP " + status,
                retryable, status, Map.of());
    }

    static LlmFailure timeout() {
        return new LlmFailure("timeout", "Model provider stream exceeded its deadline", true,
                null, Map.of());
    }

    static LlmFailure overloaded() {
        return new LlmFailure("overloaded", "Model provider stream capacity is exhausted", true,
                null, Map.of());
    }

    static LlmFailure classify(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof HttpTimeoutException) {
            return timeout();
        }
        if (error instanceof StreamAssembler.ProviderException provider) {
            return new LlmFailure("provider_error", "Model provider reported a stream error",
                    provider.retryable(), null, Map.of());
        }
        if (error instanceof StreamAssembler.ProtocolException) {
            return new LlmFailure("invalid_stream", "Model provider returned an invalid or incomplete stream",
                    false, null, Map.of());
        }
        if (error instanceof IOException || error instanceof InterruptedException) {
            return new LlmFailure("transport_error", "Model provider transport failed", true,
                    null, Map.of());
        }
        return new LlmFailure("invalid_request", "Model provider request could not be processed",
                false, null, Map.of());
    }
}
