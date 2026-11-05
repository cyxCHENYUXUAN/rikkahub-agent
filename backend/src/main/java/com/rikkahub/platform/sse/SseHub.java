package com.rikkahub.platform.sse;

import com.rikkahub.agent.event.AgentEvent;
import com.rikkahub.agent.event.AgentEventSink;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.TransactionRunner;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable database replay and bounded, serial delivery; emit never does I/O.
 */
@Component
public final class SseHub implements AgentEventSink, AutoCloseable {
    private static final int MAX_CONNECTIONS = 32;
    private static final int QUEUE_CAPACITY = 256;
    private static final long EMITTER_TIMEOUT_MS = 120000L;
    private final Object lifecycle = new Object();
    private final Map<SessionId, Set<Connection>> listeners = new ConcurrentHashMap<>();
    // Closing connections retain their admission slot until their sender exits.
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final SessionEventReplayReader replay;
    private final EventCodec codec;
    private final Clock clock;
    private final ThreadPoolExecutor senders;
    private final ScheduledExecutorService heartbeats;
    private boolean closed;

    public SseHub(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec, Clock clock) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        replay = new SessionEventReplayReader(Objects.requireNonNull(jdbc, "jdbc"),
                Objects.requireNonNull(transactions, "transactions"), codec);
        senders = new ThreadPoolExecutor(MAX_CONNECTIONS, MAX_CONNECTIONS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_CONNECTIONS), threads("rikka-sse-send-"),
                new ThreadPoolExecutor.AbortPolicy());
        heartbeats = Executors.newSingleThreadScheduledExecutor(threads("rikka-sse-heartbeat-"));
        heartbeats.scheduleAtFixedRate(() -> {
            for (Connection connection : connections)
                connection.offer(Heartbeat.INSTANCE);
        }, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(SessionId sessionId, long after) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (after < 0)
            throw new IllegalArgumentException("SSE cursor must be non-negative");
        synchronized (lifecycle) {
            if (closed)
                throw new RejectedExecutionException("SSE hub is closed");
            if (connections.size() >= MAX_CONNECTIONS)
                throw new RejectedExecutionException("SSE connection limit reached");
            Connection connection = new Connection(sessionId, after);
            // InitialReplay is already at the head before any producer can see this
            // connection.
            connections.add(connection);
            listeners.compute(sessionId, (ignored, subscribers) -> {
                Set<Connection> next = subscribers == null ? ConcurrentHashMap.newKeySet() : subscribers;
                next.add(connection);
                return next;
            });
            return connection.emitter;
        }
    }

    @Override
    public void emit(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        Delivery delivery = delivery(event);
        if (delivery == null)
            return;
        Set<Connection> subscribers = listeners.get(delivery.session());
        if (subscribers == null)
            return;
        for (Connection connection : subscribers)
            connection.offer(delivery.notice());
    }

    private static Delivery delivery(AgentEvent event) {
        if (event instanceof AgentEvent.SessionCommitted committed) {
            return new Delivery(SessionId.of(committed.sessionId()), new Committed(committed.seq()));
        }
        if (event instanceof AgentEvent.AssistantDelta delta) {
            return ephemeral(delta.sessionId(), "assistant.delta", Map.of("turn", delta.turn(), "step", delta.step(),
                    "block", delta.blockIndex(), "text", delta.text()), true);
        }
        if (event instanceof AgentEvent.AssistantReasoning reasoning) {
            return ephemeral(reasoning.sessionId(), "assistant.reasoning", Map.of("turn", reasoning.turn(),
                    "step", reasoning.step(), "block", reasoning.blockIndex(), "text", reasoning.text()), true);
        }
        if (event instanceof AgentEvent.ToolCallStarted call) {
            return ephemeral(call.sessionId(), "tool.call", call.call(), false);
        }
        if (event instanceof AgentEvent.ToolResultReady result) {
            return ephemeral(result.sessionId(), "tool.result",
                    Map.of("callId", result.callId(), "result", result.result()), false);
        }
        if (event instanceof AgentEvent.ApprovalRequested request) {
            return ephemeral(request.sessionId(), "approval.request", request.request(), false);
        }
        if (event instanceof AgentEvent.ApprovalResolved resolved) {
            return ephemeral(resolved.sessionId(), "approval.resolved", Map.of("approvalId", resolved.approvalId(),
                    "outcome", resolved.outcome()), false);
        }
        if (event instanceof AgentEvent.UsageUpdated usage) {
            return ephemeral(usage.sessionId(), "usage",
                    Map.of("turn", usage.turn(), "step", usage.step(), "usage", usage.usage()), false);
        }
        if (event instanceof AgentEvent.TurnEnded ended) {
            return ephemeral(ended.sessionId(), "turn.ended", Map.of("turn", ended.turn(), "reason", ended.reason()),
                    false);
        }
        if (event instanceof AgentEvent.Failure failure) {
            return ephemeral(failure.sessionId(), "error", Map.of("code", failure.code(), "message", failure.message()),
                    false);
        }
        return null;
    }

    private static Delivery ephemeral(String session, String name, Object data, boolean droppable) {
        return new Delivery(SessionId.of(session), new Ephemeral(name, data, droppable));
    }

    @PreDestroy
    @Override
    public void close() {
        List<Connection> snapshot;
        synchronized (lifecycle) {
            if (closed)
                return;
            closed = true;
            snapshot = new ArrayList<>(connections);
        }
        heartbeats.shutdownNow();
        for (Connection connection : snapshot)
            connection.requestClose(true);
        senders.shutdown();
        // A close invoked from a servlet completion callback may run on a sender.
        if (Thread.currentThread().getName().startsWith("rikka-sse-send-"))
            return;
        try {
            if (!senders.awaitTermination(2, TimeUnit.SECONDS)) {
                for (Runnable pending : senders.shutdownNow()) {
                    if (pending instanceof Connection connection)
                        connection.finish();
                }
                senders.awaitTermination(1, TimeUnit.SECONDS);
            }
            heartbeats.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            for (Runnable pending : senders.shutdownNow()) {
                if (pending instanceof Connection connection)
                    connection.finish();
            }
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return work -> {
            Thread thread = new Thread(work, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private sealed interface Notice permits InitialReplay, Committed, Ephemeral, Heartbeat {
    }

    private enum InitialReplay implements Notice {
        INSTANCE
    }

    private enum Heartbeat implements Notice {
        INSTANCE
    }

    private record Committed(long target) implements Notice {
    }

    private record Ephemeral(String name, Object data, boolean droppable) implements Notice {
    }

    private record Delivery(SessionId session, Notice notice) {
    }

    private final class Connection implements Runnable {
        private final SessionId session;
        private final ArrayBlockingQueue<Notice> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean finishStarted = new AtomicBoolean();
        private final AtomicBoolean completeRequested = new AtomicBoolean();
        private final SseEmitter emitter;
        private volatile boolean responseStarting;
        private long cursor;
        private long target;

        Connection(SessionId session, long after) {
            this.session = session;
            cursor = after;
            target = after;
            queue.add(InitialReplay.INSTANCE);
            emitter = new SseEmitter(EMITTER_TIMEOUT_MS) {
                @Override
                protected void extendResponse(ServerHttpResponse response) {
                    super.extendResponse(response);
                    // Avoid Spring's early-send buffer while the controller still owns the
                    // response.
                    responseStarting = true;
                    schedule();
                }
            };
            emitter.onCompletion(() -> requestClose(false));
            emitter.onTimeout(() -> requestClose(true));
            emitter.onError(error -> requestClose(false));
        }

        void offer(Notice notice) {
            if (terminal.get())
                return;
            if (!queue.offer(notice)) {
                if (notice instanceof Heartbeat || notice instanceof Ephemeral event && event.droppable())
                    return;
                requestClose(true);
                return;
            }
            if (terminal.get())
                queue.clear();
            schedule();
        }

        void requestClose(boolean complete) {
            if (complete)
                completeRequested.set(true);
            if (terminal.compareAndSet(false, true)) {
                // Drop only this closed connection's buffered delivery; reconnect replays from
                // the database.
                listeners.computeIfPresent(session, (ignored, subscribers) -> {
                    subscribers.remove(this);
                    return subscribers.isEmpty() ? null : subscribers;
                });
                queue.clear();
            }
            schedule();
        }

        private void schedule() {
            if (finishStarted.get() || !responseStarting && !terminal.get())
                return;
            if (!scheduled.compareAndSet(false, true))
                return;
            try {
                senders.execute(this);
            } catch (RejectedExecutionException rejected) {
                scheduled.set(false);
                // Admission and one outstanding task per connection bound the queue while open.
                // After shutdown, the close path already owns emitter completion.
            }
        }

        @Override
        public synchronized void run() {
            try {
                if (terminal.get())
                    return;
                if (target > cursor) {
                    sendPage();
                    return;
                }
                Notice notice = queue.poll();
                if (notice instanceof InitialReplay) {
                    target = Math.max(cursor, replay.highWater(session));
                    if (target > cursor)
                        sendPage();
                } else if (notice instanceof Committed committed) {
                    target = Math.max(cursor, committed.target());
                    if (target > cursor)
                        sendPage();
                } else if (notice instanceof Ephemeral ephemeral) {
                    send(ephemeral.name(), ephemeral.data(), null);
                } else if (notice instanceof Heartbeat) {
                    send("heartbeat", Map.of("time", clock.now()), null);
                }
            } catch (IOException | RuntimeException error) {
                requestClose(true);
            } finally {
                if (terminal.get())
                    finish();
                scheduled.set(false);
                // A close/offer racing the handoff must either schedule here or win schedule()
                // itself.
                if (!finishStarted.get() && (terminal.get() || target > cursor || !queue.isEmpty()))
                    schedule();
            }
        }

        private void sendPage() throws IOException {
            List<SessionEvent> page = replay.page(session, cursor, target);
            if (terminal.get())
                return;
            long last = page.get(page.size() - 1).seq();
            send("session.committed", Map.of("sessionId", session.value(), "seq", last, "events", page),
                    session.value() + ":" + last);
            cursor = last;
        }

        private void send(String name, Object data, String id) throws IOException {
            if (terminal.get())
                return;
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(name);
            if (id != null)
                event.id(id);
            emitter.send(event.data(codec.encode(data), MediaType.APPLICATION_JSON));
        }

        private void finish() {
            if (!finishStarted.compareAndSet(false, true))
                return;
            try {
                if (completeRequested.get())
                    emitter.complete();
            } catch (RuntimeException ignored) {
                // A container error may have already completed the response.
            } finally {
                queue.clear();
                connections.remove(this);
            }
        }
    }
}
