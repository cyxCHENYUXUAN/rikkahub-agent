package com.rikkahub.features.chat.application;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.message.StreamChunk;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.model.LlmFailure;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.model.LlmStream;
import com.rikkahub.agent.model.LlmAdapter;
import com.rikkahub.agent.model.StreamListener;

/** One-shot text collection for non-turn model calls such as summaries and extraction. */
public final class ModelTextCollector {
    private ModelTextCollector() {
    }

    public record Result(String text, TokenUsage usage, String error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    public static Result collect(LlmAdapter adapter, LlmRequest request, CancellationToken signal,
                                 Duration timeout) {
        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.zero());
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (LlmStream stream = adapter.stream(request, signal)) {
            AutoCloseable cancel = signal.onCancel(() -> {
                failure.compareAndSet(null, "cancelled");
                done.countDown();
            });
            stream.subscribe(new StreamListener() {
                @Override public void onChunk(StreamChunk chunk) {
                    if (chunk instanceof StreamChunk.TextDelta delta) {
                        text.append(delta.text());
                    } else if (chunk instanceof StreamChunk.Usage value) {
                        usage.set(value.usage());
                    } else if (chunk instanceof StreamChunk.Failed failed) {
                        failure.compareAndSet(null, failed.code() + ": " + failed.message());
                    } else if (chunk instanceof StreamChunk.Cancelled) {
                        failure.compareAndSet(null, "cancelled");
                    }
                }

                @Override public void onFailure(LlmFailure error) {
                    failure.compareAndSet(null, error == null ? "model_failed"
                            : error.code() + ": " + error.message());
                    done.countDown();
                }

                @Override public void onComplete() {
                    done.countDown();
                }
            });
            long millis = Math.max(1L, timeout.toMillis());
            boolean finished = done.await(millis, TimeUnit.MILLISECONDS);
            try {
                cancel.close();
            } catch (Exception ignored) {
                // Cancellation registration detach only.
            }
            if (!finished) return new Result("", usage.get(), "timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result("", usage.get(), "interrupted");
        } catch (RuntimeException failureError) {
            return new Result("", usage.get(), "model_error");
        }
        String problem = failure.get();
        if (problem != null) return new Result("", usage.get(), problem);
        if (text.toString().isBlank()) return new Result("", usage.get(), "empty_response");
        return new Result(text.toString(), usage.get(), null);
    }
}
