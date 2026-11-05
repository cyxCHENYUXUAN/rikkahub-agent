package com.rikkahub.agent.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import com.rikkahub.agent.api.AgentContext;
import com.rikkahub.agent.api.CancelCause;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.api.CancelledException;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.config.ToolPolicy;
import com.rikkahub.agent.event.AgentEvent;
import com.rikkahub.agent.event.AgentEventSink;
import com.rikkahub.agent.message.FinishReason;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.message.StreamChunk;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.model.LlmFailure;
import com.rikkahub.agent.model.LlmAdapter;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.model.LlmStream;
import com.rikkahub.agent.model.StreamListener;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.tool.ToolRegistry;
import com.rikkahub.agent.tool.ToolSchema;
import com.rikkahub.agent.tool.ToolScope;

/**
 * Assembles the frozen request for one step and collects one model stream.
 * A request is built once per step; retries reuse the same instance.
 */
public final class StepDriver {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS,
                    DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                    DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .build();

    private final AgentContext context;
    private final LlmAdapter llm;
    private final ToolRegistry tools;
    private final AgentEventSink events;
    private final Clock clock;
    private final IdGenerator ids;
    private final CancellationToken signal;

    StepDriver(AgentContext context, LlmAdapter llm, ToolRegistry tools,
               AgentEventSink events, Clock clock, IdGenerator ids, CancellationToken signal) {
        this.context = Objects.requireNonNull(context, "context");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    LlmRequest buildRequest(List<Message> messages, ToolScope scope, int turn, int step) {
        AgentConfigSnapshot config = context.config();
        ToolPolicy policy = config.tools();
        List<ToolSchema> schemas = tools.schemas(scope);
        if (!policy.allowAll()) {
            List<ToolSchema> filtered = new ArrayList<>();
            for (ToolSchema schema : schemas) {
                if (policy.allowedTools().contains(schema.name())) {
                    filtered.add(schema);
                }
            }
            schemas = List.copyOf(filtered);
        }
        return new LlmRequest(
                ids.newId("req"),
                config.model(),
                null,
                messages,
                schemas,
                config.generation(),
                Map.of(
                        "turn", turn,
                        "step", step,
                        "configSnapshot", JSON.valueToTree(config),
                        "contextWindowTokens", config.context().contextWindowTokens()),
                clock.now());
    }

    String serialize(LlmRequest request) {
        try {
            return JSON.writeValueAsString(request);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize model request", ex);
        }
    }

    /**
     * Subscribes to one stream attempt and blocks until it terminates or the
     * turn timeout elapses. Deltas are emitted as ephemeral events while the
     * durable message is assembled here.
     */
    CollectedStream collect(LlmRequest request, int turn, int step) {
        String sessionId = context.sessionId().value();

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.zero());
        AtomicReference<FinishReason> finish = new AtomicReference<>();
        AtomicReference<LlmFailure> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(signal.isCancelled());
        AtomicBoolean terminated = new AtomicBoolean();
        ConcurrentSkipListMap<Integer, ToolCallBuilder> calls = new ConcurrentSkipListMap<>();
        CountDownLatch done = new CountDownLatch(1);
        Object stateLock = new Object();
        CancellationToken streamSignal = CancellationToken.none();

        StreamListener listener = new StreamListener() {
            @Override
            public void onChunk(StreamChunk chunk) {
                synchronized (stateLock) {
                    if (terminated.get()) {
                        return;
                    }
                    if (signal.isCancelled()) {
                        cancelled.set(true);
                        terminate(terminated, done);
                        return;
                    }
                    if (finish.get() != null && (chunk instanceof StreamChunk.TextDelta
                            || chunk instanceof StreamChunk.ReasoningDelta
                            || chunk instanceof StreamChunk.ToolCallDelta)) {
                        // Finished closes content assembly; usage may arrive until onComplete.
                        return;
                    }
                    if (chunk instanceof StreamChunk.TextDelta delta) {
                        text.append(delta.text());
                        emit(events, new AgentEvent.AssistantDelta(sessionId, turn, step, 0,
                                delta.text()));
                    } else if (chunk instanceof StreamChunk.ReasoningDelta delta) {
                        reasoning.append(delta.text());
                        emit(events, new AgentEvent.AssistantReasoning(sessionId, turn, step, 0,
                                delta.text()));
                    } else if (chunk instanceof StreamChunk.ToolCallDelta delta) {
                        try {
                            calls.computeIfAbsent(delta.index(), ToolCallBuilder::new).merge(delta);
                        } catch (IllegalArgumentException ex) {
                            failure.set(new LlmFailure("stream_error", message(ex), false, null,
                                    Map.of()));
                            terminate(terminated, done);
                        }
                    } else if (chunk instanceof StreamChunk.Usage chunkUsage) {
                        usage.set(chunkUsage.usage());
                        emit(events, new AgentEvent.UsageUpdated(sessionId, turn, step,
                                chunkUsage.usage()));
                    } else if (chunk instanceof StreamChunk.Finished finished) {
                        finish.compareAndSet(null, finished.reason());
                        if (finished.reason() == FinishReason.CANCELLED) {
                            cancelled.set(true);
                            terminate(terminated, done);
                        }
                    } else if (chunk instanceof StreamChunk.Failed failed) {
                        failure.compareAndSet(null, new LlmFailure(failed.code(), failed.message(),
                                failed.retryable(), null, Map.of()));
                        terminate(terminated, done);
                    } else if (chunk instanceof StreamChunk.Cancelled) {
                        cancelled.set(true);
                        terminate(terminated, done);
                    }
                }
            }

            @Override
            public void onFailure(LlmFailure llmFailure) {
                synchronized (stateLock) {
                    if (terminated.get()) {
                        return;
                    }
                    if (signal.isCancelled()) {
                        cancelled.set(true);
                    } else {
                        failure.set(llmFailure == null
                                ? new LlmFailure("stream_error", "stream reported no failure details",
                                        false, null, Map.of()) : llmFailure);
                    }
                    terminate(terminated, done);
                }
            }

            @Override
            public void onComplete() {
                synchronized (stateLock) {
                    if (!terminated.get()) {
                        if (signal.isCancelled()) {
                            cancelled.set(true);
                        } else {
                            finish.compareAndSet(null, FinishReason.STOP);
                        }
                        terminate(terminated, done);
                    }
                }
            }
        };

        AtomicReference<LlmStream> liveStream = new AtomicReference<>();
        AutoCloseable cancelHandle = null;
        if (signal.isCancelled()) {
            cancelled.set(true);
            return result(text, reasoning, usage, finish, failure, cancelled, calls);
        }
        try {
            cancelHandle = signal.onCancel(() -> {
                synchronized (stateLock) {
                    if (!terminated.get()) {
                        cancelled.set(true);
                        terminate(terminated, done);
                    }
                }
                streamSignal.cancel(signal.cause());
                closeQuietly(liveStream.getAndSet(null));
            });
            if (!signal.isCancelled()) {
                LlmStream stream = Objects.requireNonNull(llm.stream(request, streamSignal), "model stream");
                liveStream.set(stream);
                if (signal.isCancelled()) {
                    closeQuietly(liveStream.getAndSet(null));
                } else {
                    stream.subscribe(listener);
                }
            }
            boolean completed = done.await(timeoutMillis(), TimeUnit.MILLISECONDS);
            synchronized (stateLock) {
                if (!completed && !terminated.get()) {
                    if (signal.isCancelled()) {
                        cancelled.set(true);
                    } else {
                        failure.set(new LlmFailure("step_timeout",
                                "model stream did not complete within " + timeoutMillis() + " ms",
                                true, null, Map.of()));
                    }
                    terminate(terminated, done);
                }
            }
            if (!completed) {
                streamSignal.cancel(CancelCause.TIMEOUT);
            }
        } catch (CancelledException ex) {
            synchronized (stateLock) {
                if (!terminated.get()) {
                    cancelled.set(true);
                    terminate(terminated, done);
                }
            }
            streamSignal.cancel(ex.cancelCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            synchronized (stateLock) {
                if (!terminated.get()) {
                    cancelled.set(true);
                    terminate(terminated, done);
                }
            }
            streamSignal.cancel(CancelCause.PARENT);
        } catch (RuntimeException ex) {
            synchronized (stateLock) {
                if (!terminated.get()) {
                    if (signal.isCancelled()) {
                        cancelled.set(true);
                    } else {
                        failure.set(new LlmFailure("stream_error", message(ex), false,
                                null, Map.of()));
                    }
                    terminate(terminated, done);
                }
            }
        } finally {
            CancelCause stopCause;
            synchronized (stateLock) {
                terminate(terminated, done);
                stopCause = cancelled.get()
                        ? (signal.cause() == null ? CancelCause.UNKNOWN : signal.cause())
                        : failure.get() == null ? null : CancelCause.ERROR;
            }
            if (stopCause != null) {
                streamSignal.cancel(stopCause);
            }
            closeQuietly(cancelHandle);
            closeQuietly(liveStream.getAndSet(null));
        }

        synchronized (stateLock) {
            return result(text, reasoning, usage, finish, failure, cancelled, calls);
        }
    }

    private CollectedStream result(StringBuilder text, StringBuilder reasoning,
                                   AtomicReference<TokenUsage> usage,
                                   AtomicReference<FinishReason> finish,
                                   AtomicReference<LlmFailure> failure,
                                   AtomicBoolean cancelled,
                                   ConcurrentSkipListMap<Integer, ToolCallBuilder> calls) {
        List<AssembledToolCall> toolCalls = new ArrayList<>(calls.size());
        for (ToolCallBuilder builder : calls.values()) {
            toolCalls.add(builder.build(ids));
        }
        return new CollectedStream(text.toString(), reasoning.toString(), usage.get(),
                finish.get(), failure.get(), cancelled.get(), toolCalls);
    }

    private long timeoutMillis() {
        Duration timeout = context.config().turnTimeout();
        try {
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static void terminate(AtomicBoolean terminated, CountDownLatch done) {
        if (terminated.compareAndSet(false, true)) {
            done.countDown();
        }
    }

    private static void emit(AgentEventSink sink, AgentEvent event) {
        try {
            sink.emit(event);
        } catch (RuntimeException ignored) {
            // A live listener must not be able to abort the turn.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort; the stream is already terminal.
        }
    }

    private static String message(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String text = throwable.getMessage();
        return text == null || text.isBlank() ? throwable.getClass().getSimpleName() : text;
    }

    /** Result of one stream attempt; immutable and safe to consume after await. */
    record CollectedStream(String text, String reasoning, TokenUsage usage,
                           FinishReason finishReason, LlmFailure failure,
                           boolean cancelled, List<AssembledToolCall> toolCalls) {
        CollectedStream {
            text = text == null ? "" : text;
            reasoning = reasoning == null ? "" : reasoning;
            usage = usage == null ? TokenUsage.zero() : usage;
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        }
    }

    /** A tool call assembled from streamed argument fragments. */
    record AssembledToolCall(int index, String callId, String toolName, String argumentsJson) {
        AssembledToolCall {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(argumentsJson, "argumentsJson");
        }
    }

    private static final class ToolCallBuilder {
        private final int index;
        private String callId;
        private String toolName;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallBuilder(int index) {
            this.index = index;
        }

        private synchronized void merge(StreamChunk.ToolCallDelta delta) {
            if (callId != null && delta.callId() != null && !callId.equals(delta.callId())) {
                throw new IllegalArgumentException("conflicting call ID for tool index " + index);
            }
            if (toolName != null && delta.toolName() != null && !toolName.equals(delta.toolName())) {
                throw new IllegalArgumentException("conflicting tool name for tool index " + index);
            }
            if (callId == null) {
                callId = delta.callId();
            }
            if (toolName == null) {
                toolName = delta.toolName();
            }
            if (delta.argumentsFragment() != null) {
                arguments.append(delta.argumentsFragment());
            }
        }

        private synchronized AssembledToolCall build(IdGenerator ids) {
            String resolvedCallId = callId == null ? ids.newId("call") : callId;
            String resolvedName = toolName == null ? "unknown_tool" : toolName;
            return new AssembledToolCall(index, resolvedCallId, resolvedName, arguments.toString());
        }
    }
}
