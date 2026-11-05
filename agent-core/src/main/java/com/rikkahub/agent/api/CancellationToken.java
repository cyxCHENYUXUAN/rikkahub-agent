package com.rikkahub.agent.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cooperative cancellation that crosses model streams, tools, approvals and
 * compaction. A child token is cancelled when its parent is cancelled.
 */
public final class CancellationToken {
    private final AtomicReference<CancelCause> cause = new AtomicReference<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private CancellationToken() {
    }

    private CancellationToken(CancellationToken parent) {
        AutoCloseable parentRegistration = parent.onCancel(() -> cancel(CancelCause.PARENT));
        onCancel(() -> {
            try {
                parentRegistration.close();
            } catch (Exception ignored) {
                // Detaching a child must not interfere with cancellation propagation.
            }
        });
    }

    public static CancellationToken none() {
        return new CancellationToken();
    }

    public boolean isCancelled() {
        return cause.get() != null;
    }

    public CancelCause cause() {
        return cause.get();
    }

    /**
     * Registers a listener. If the token is already cancelled the listener runs
     * immediately. Closing the returned handle removes the listener.
     */
    public AutoCloseable onCancel(Runnable listener) {
        Listener registration = new Listener(Objects.requireNonNull(listener, "listener"));
        listeners.add(registration);
        if (isCancelled()) {
            listeners.remove(registration);
            registration.fire();
        }
        return () -> {
            registration.remove();
            listeners.remove(registration);
        };
    }

    public void throwIfCancelled() {
        CancelCause current = cause.get();
        if (current != null) {
            throw new CancelledException(current);
        }
    }

    public CancellationToken child() {
        return new CancellationToken(this);
    }

    /** First cancellation wins; subsequent calls are no-ops. */
    public boolean cancel(CancelCause cancelCause) {
        CancelCause value = cancelCause == null ? CancelCause.UNKNOWN : cancelCause;
        if (!cause.compareAndSet(null, value)) {
            return false;
        }
        try {
            for (Listener listener : listeners) {
                listener.fire();
            }
        } finally {
            listeners.clear();
        }
        return true;
    }

    /** Either cancellation path may see an entry; only one may take its callback. */
    private static final class Listener {
        private final AtomicReference<Runnable> callback;

        private Listener(Runnable callback) {
            this.callback = new AtomicReference<>(callback);
        }

        private void fire() {
            Runnable action = callback.getAndSet(null);
            if (action != null) {
                runSafely(action);
            }
        }

        private void remove() {
            callback.set(null);
        }
    }

    private static void runSafely(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // A listener must not prevent other listeners from observing cancellation.
        }
    }
}
