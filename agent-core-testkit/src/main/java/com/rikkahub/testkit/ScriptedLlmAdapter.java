package com.rikkahub.testkit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.message.FinishReason;
import com.rikkahub.agent.message.StreamChunk;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.model.LlmAdapter;
import com.rikkahub.agent.model.LlmFailure;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.model.LlmStream;
import com.rikkahub.agent.model.ModelCapability;
import com.rikkahub.agent.model.ModelInfo;
import com.rikkahub.agent.model.ProviderInfo;
import com.rikkahub.agent.model.StreamListener;

/**
 * Deterministic test double for {@link LlmAdapter}.
 * Allows scripting a sequence of model responses (text, reasoning, tool calls, errors, cancellations)
 * and asserting against all received {@link LlmRequest} instances without requiring external API keys.
 */
public final class ScriptedLlmAdapter implements LlmAdapter {

    public interface ScriptedAction {
        void execute(StreamListener listener, CancellationToken signal);
    }

    private final ProviderInfo providerInfo;
    private final List<ModelInfo> models;
    private final Queue<ScriptedAction> scriptedQueue = new ArrayDeque<>();
    private final List<LlmRequest> capturedRequests = new CopyOnWriteArrayList<>();

    public ScriptedLlmAdapter() {
        this("scripted-provider", "Scripted Test Provider", "scripted-model-1");
    }

    public ScriptedLlmAdapter(String providerId, String providerName, String modelId) {
        this.providerInfo = new ProviderInfo(
                providerId,
                providerName,
                Set.of(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOLS, ModelCapability.REASONING)
        );
        this.models = List.of(new ModelInfo(modelId, "Scripted Model", 128_000, 4096, true, true));
    }

    @Override
    public ProviderInfo provider() {
        return providerInfo;
    }

    @Override
    public List<ModelInfo> models() {
        return models;
    }

    @Override
    public LlmStream stream(LlmRequest request, CancellationToken signal) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(signal, "signal");
        capturedRequests.add(request);

        ScriptedAction action;
        synchronized (scriptedQueue) {
            action = scriptedQueue.poll();
        }

        if (action == null) {
            // Default response if queue is empty: simple OK text
            action = (listener, sig) -> {
                listener.onChunk(new StreamChunk.TextDelta("OK"));
                listener.onChunk(new StreamChunk.Usage(TokenUsage.of(10, 2)));
                listener.onChunk(new StreamChunk.Finished(FinishReason.STOP));
                listener.onComplete();
            };
        }

        ScriptedAction selectedAction = action;
        return new LlmStream() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public void subscribe(StreamListener listener) {
                Objects.requireNonNull(listener, "listener");
                if (closed.get()) {
                    return;
                }
                selectedAction.execute(listener, signal);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    public ScriptedLlmAdapter enqueueText(String text) {
        return enqueueChunks(
                new StreamChunk.TextDelta(text),
                new StreamChunk.Usage(TokenUsage.of(10, text.length() / 4 + 1)),
                new StreamChunk.Finished(FinishReason.STOP)
        );
    }

    public ScriptedLlmAdapter enqueueReasoningAndText(String reasoning, String text) {
        return enqueueChunks(
                new StreamChunk.ReasoningDelta(reasoning),
                new StreamChunk.TextDelta(text),
                new StreamChunk.Usage(TokenUsage.of(10, (reasoning.length() + text.length()) / 4 + 1)),
                new StreamChunk.Finished(FinishReason.STOP)
        );
    }

    public ScriptedLlmAdapter enqueueToolCall(String callId, String toolName, String argumentsJson) {
        return enqueueChunks(
                new StreamChunk.ToolCallDelta(0, callId, toolName, argumentsJson),
                new StreamChunk.Usage(TokenUsage.of(15, 8)),
                new StreamChunk.Finished(FinishReason.TOOL_CALLS)
        );
    }

    public ScriptedLlmAdapter enqueueChunks(StreamChunk... chunks) {
        List<StreamChunk> list = List.of(chunks);
        synchronized (scriptedQueue) {
            scriptedQueue.add((listener, signal) -> {
                if (signal != null && signal.isCancelled()) {
                    listener.onChunk(new StreamChunk.Cancelled());
                    listener.onComplete();
                    return;
                }
                for (StreamChunk chunk : list) {
                    if (signal != null && signal.isCancelled()) {
                        listener.onChunk(new StreamChunk.Cancelled());
                        listener.onComplete();
                        return;
                    }
                    listener.onChunk(chunk);
                }
                listener.onComplete();
            });
        }
        return this;
    }

    public ScriptedLlmAdapter enqueueFailure(String code, String message, boolean retryable) {
        synchronized (scriptedQueue) {
            scriptedQueue.add((listener, signal) -> {
                listener.onFailure(new LlmFailure(code, message, retryable, 500, java.util.Map.of()));
            });
        }
        return this;
    }

    public ScriptedLlmAdapter enqueueCustomAction(ScriptedAction action) {
        Objects.requireNonNull(action, "action");
        synchronized (scriptedQueue) {
            scriptedQueue.add(action);
        }
        return this;
    }

    public List<LlmRequest> capturedRequests() {
        return new ArrayList<>(capturedRequests);
    }

    public LlmRequest lastRequest() {
        if (capturedRequests.isEmpty()) {
            throw new IllegalStateException("No requests have been captured yet");
        }
        return capturedRequests.get(capturedRequests.size() - 1);
    }

    public int requestCount() {
        return capturedRequests.size();
    }

    public void clearRequests() {
        capturedRequests.clear();
    }

    public void clearQueue() {
        synchronized (scriptedQueue) {
            scriptedQueue.clear();
        }
    }
}
