package com.rikkahub.agent.loop;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.rikkahub.agent.api.Agent;
import com.rikkahub.agent.api.AgentContext;
import com.rikkahub.agent.api.AgentId;
import com.rikkahub.agent.api.AgentStatus;
import com.rikkahub.agent.api.CancelCause;
import com.rikkahub.agent.api.CancelOptions;
import com.rikkahub.agent.api.CancelResult;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.port.ClaimedInput;
import com.rikkahub.agent.port.PendingInput;
import com.rikkahub.agent.scope.AgentScope;
import com.rikkahub.agent.session.InputId;
import com.rikkahub.agent.session.SessionId;

/** Serializes wakeups while the session log remains owned by AgentLoop. */
public final class LoopAgent implements Agent, AutoCloseable {
    private final Object lock = new Object();
    private final AgentId id;
    private final SessionId sessionId;
    private final RuntimeServices services;
    private final ExecutorService executor;
    private final Consumer<LoopAgent> onDispose;
    private final AgentScope scope = new AgentScope();
    private final CancellationToken lifetime = CancellationToken.none();
    private final AgentContext context;
    private volatile AgentStatus status = AgentStatus.IDLE;
    private volatile CancellationToken active;
    private volatile AgentLoop loop;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> stopped = CompletableFuture.completedFuture(null);
    private boolean scheduled;
    private boolean wakeRequested;
    private boolean disposed;
    private boolean resourcesClosed;
    private boolean quiescent = true;
    private Throwable terminalFailure;
    private Thread worker;
    private AutoCloseable parentRegistration;

    LoopAgent(AgentId id, SessionId sessionId, AgentConfigSnapshot config, ExecutionEnvironment environment,
              RuntimeServices services, ExecutorService executor, Consumer<LoopAgent> onDispose) {
        this.id = id;
        this.sessionId = sessionId;
        this.services = services;
        this.executor = executor;
        this.onDispose = onDispose;
        this.context = new AgentContext() {
            public AgentId agentId() {
                return id;
            }

            public SessionId sessionId() {
                return sessionId;
            }

            public AgentConfigSnapshot config() {
                return config;
            }

            public ExecutionEnvironment environment() {
                return environment;
            }

            public AgentScope scope() {
                return scope;
            }

            public CancellationToken cancellation() {
                CancellationToken current = active;
                return current == null ? lifetime : current;
            }
        };
    }

    void bindParent(CancellationToken parent) {
        parentRegistration = parent.onCancel(this::close);
    }

    public AgentId id() {
        return id;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public AgentStatus status() {
        return status;
    }

    public AgentContext context() {
        return context;
    }

    public void followup(InboxItem item) {
        send(item, InboxTarget.NEXT_TURN);
    }

    public void steer(InboxItem item) {
        send(item, InboxTarget.NEXT_STEP);
    }

    public void send(InboxItem item, InboxTarget target) {
        enqueue(item, target, true);
    }

    public void inject(InboxItem item) {
        Objects.requireNonNull(item, "item");
        Map<String, Object> metadata = new HashMap<>(item.metadata());
        metadata.put("wake", false);
        enqueue(new InboxItem(item.id(), item.content(), metadata), InboxTarget.NEXT_STEP, false);
    }

    private void enqueue(InboxItem item, InboxTarget target, boolean wake) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(target, "target");
        synchronized (lock) {
            ensureOpen();
            if (wake && Boolean.FALSE.equals(item.metadata().get("wake"))) {
                Map<String, Object> metadata = new HashMap<>(item.metadata());
                metadata.remove("wake");
                item = new InboxItem(item.id(), item.content(), metadata);
            }
            services.inbox().enqueue(new PendingInput(new InputId(item.id()), sessionId, target,
                    item, services.clock().now()));
            if (wake) {
                wakeRequested = true;
                schedule();
            }
        }
    }

    private void schedule() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        wakeRequested = false;
        status = AgentStatus.RUNNING;
        if (idle.isDone()) {
            idle = new CompletableFuture<>();
        }
        stopped = new CompletableFuture<>();
        CancellationToken token = CancellationToken.none();
        active = token;
        loop = new AgentLoop(context, services, token);
        try {
            executor.execute(this::work);
        } catch (RuntimeException ex) {
            scheduled = false;
            active = null;
            status = AgentStatus.IDLE;
            idle.completeExceptionally(ex);
            terminalFailure = ex;
            stopped.completeExceptionally(ex);
            throw ex;
        }
    }

    private void work() {
        Throwable failure = null;
        AgentLoop currentLoop;
        synchronized (lock) {
            worker = Thread.currentThread();
            currentLoop = loop;
            quiescent = false;
        }
        try {
            currentLoop.runTurnLoop();
        } catch (Throwable ex) {
            failure = ex;
        }
        boolean drained = false;
        boolean pending = false;
        try {
            // A cancellation request does not prove that a side effect stopped.
            currentLoop.drainTools();
            drained = true;
        } catch (Throwable ex) {
            failure = combine(failure, ex);
        }

        CompletableFuture<Void> completed;
        CompletableFuture<Void> idleToComplete = null;
        boolean releaseResources;
        synchronized (lock) {
            // Read pending state while holding the same monitor as enqueue,
            // otherwise a send between the read and scheduling can be lost.
            if (failure == null) {
                try {
                    pending = hasWakeableInput();
                } catch (Throwable ex) {
                    failure = ex;
                }
            }
            scheduled = false;
            active = null;
            quiescent = drained;
            terminalFailure = failure;
            completed = stopped;
            releaseResources = disposed && drained;
            if (!disposed) {
                status = drained ? AgentStatus.IDLE : AgentStatus.DISPOSED;
            }
            if (failure != null || disposed || !pending) {
                idleToComplete = idle;
                idle = new CompletableFuture<>();
                if (failure == null) {
                    idle.complete(null);
                } else {
                    idle.completeExceptionally(failure);
                }
            }
            if (!disposed && failure == null && wakeRequested && pending) {
                try {
                    schedule();
                } catch (Throwable ex) {
                    failure = ex;
                }
            }
        }
        if (releaseResources) {
            try {
                closeResources();
            } catch (Throwable ex) {
                failure = combine(failure, ex);
            }
        }
        // Futures always terminate even when a borrowed storage/drain service
        // fails. An unconfirmed drain retains the live-writer reservation.
        if (failure == null) {
            completed.complete(null);
        } else {
            completed.completeExceptionally(failure);
        }
        synchronized (lock) {
            if (worker == Thread.currentThread()) {
                worker = null;
            }
        }
        if (idleToComplete != null) {
            if (failure == null) {
                idleToComplete.complete(null);
            } else {
                idleToComplete.completeExceptionally(failure);
            }
        }
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    public CancelResult cancel(CancelOptions options) {
        Objects.requireNonNull(options, "options");
        CancellationToken token;
        int turn;
        synchronized (lock) {
            ensureOpen();
            token = active;
            if (options.expectedTurn() != null) {
                token = token == null ? null : loop.cancellationForTurn(options.expectedTurn());
                if (token == null) {
                    return new CancelResult.NothingToCancel();
                }
            }
            wakeRequested = false;
            if (!options.keepInbox()) {
                discardPending();
            }
            if (token == null) {
                return new CancelResult.NothingToCancel();
            }
            if (token.isCancelled()) {
                return new CancelResult.AlreadyCancelling();
            }
            turn = loop.currentTurn();
        }
        return token.cancel(options.cause())
                ? new CancelResult.Cancelled(turn)
                : new CancelResult.AlreadyCancelling();
    }

    public CompletableFuture<Void> whenIdle() {
        synchronized (lock) {
            return idle.copy();
        }
    }

    public void runMaintenance() {
        synchronized (lock) {
            ensureOpen();
            if (hasWakeableInput()) {
                wakeRequested = true;
                schedule();
            }
        }
    }

    @Override
    public void close() {
        CompletableFuture<Void> wait;
        CancellationToken token;
        boolean sameThread;
        boolean closeNow;
        synchronized (lock) {
            disposed = true;
            status = AgentStatus.DISPOSED;
            wakeRequested = false;
            token = active;
            wait = stopped;
            sameThread = Thread.currentThread() == worker;
            closeNow = !scheduled && quiescent;
        }
        lifetime.cancel(CancelCause.SHUTDOWN);
        if (token != null) {
            token.cancel(CancelCause.SHUTDOWN);
        }
        if (closeNow) {
            closeResources();
        }
        if (!sameThread) {
            wait.join();
        }
        if (closeNow) {
            CompletableFuture<Void> completion;
            synchronized (lock) {
                completion = idle;
            }
            completion.complete(null);
        }
    }

    private void closeResources() {
        synchronized (lock) {
            if (resourcesClosed) {
                return;
            }
            resourcesClosed = true;
        }
        try {
            if (parentRegistration != null) {
                parentRegistration.close();
            }
        } catch (Exception ignored) {
            /* already terminal */
        }
        scope.close();
        onDispose.accept(this);
    }

    private boolean hasWakeableInput() {
        return services.inbox().listPending(sessionId).stream()
                .anyMatch(i -> !Boolean.FALSE.equals(i.item().metadata().get("wake")));
    }

    private void discardPending() {
        ClaimedInput input;
        while ((input = services.inbox().claim(sessionId, id.value(), Duration.ofSeconds(30))) != null) {
            services.inbox().release(input.token(), false);
        }
    }

    private void ensureOpen() {
        if (disposed) {
            throw new IllegalStateException("agent is disposed");
        }
        if (terminalFailure != null) {
            throw new IllegalStateException("agent requires recovery after an infrastructure failure", terminalFailure);
        }
    }
}
