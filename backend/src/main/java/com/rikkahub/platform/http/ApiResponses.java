package com.rikkahub.platform.http;

import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import com.rikkahub.platform.idempotency.IdempotencyService.Result;
import org.springframework.http.ResponseEntity;

public final class ApiResponses {
    private ApiResponses() {
    }

    public static <T> T await(CompletableFuture<T> future) {
        return await(future, 15);
    }

    public static <T> T await(CompletableFuture<T> future, long seconds) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiFailure(503, "INTERRUPTED", "The request was interrupted.");
        } catch (TimeoutException error) {
            throw new ApiFailure(503, "OVERLOADED",
                    "The request is still queued; retry with the same idempotency key.");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime)
                throw runtime;
            throw new ApiFailure(500, "INTERNAL_ERROR", "The request could not be completed.");
        }
    }

    public static ResponseEntity<JsonNode> response(Result result) {
        return ResponseEntity.status(result.status())
                .body(result.body() == null || result.body().isNull() ? null : result.body());
    }
}
