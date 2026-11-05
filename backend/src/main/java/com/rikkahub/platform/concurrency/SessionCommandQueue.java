package com.rikkahub.platform.concurrency;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Bounded, non-inline command execution; idle keys retain no queue or thread.
 */
@Component
public final class SessionCommandQueue implements AutoCloseable {
    private final Map<String, ArrayDeque<Runnable>> queues = new HashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), r -> {
                Thread t = new Thread(r, "session-command");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;
    private int pending;

    public <T> CompletableFuture<T> submit(String key, Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (queues) {
            if (closed || pending >= 256)
                throw new RejectedExecutionException("command queue unavailable");
            ArrayDeque<Runnable> queue = queues.computeIfAbsent(key, k -> new ArrayDeque<>());
            if (queue.size() >= 32)
                throw new RejectedExecutionException("session command queue full");
            queue.add(() -> {
                try {
                    result.complete(work.call());
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            pending++;
            if (queue.size() == 1) {
                try {
                    workers.execute(() -> drain(key));
                } catch (RuntimeException error) {
                    queue.removeLast();
                    pending--;
                    queues.remove(key);
                    throw error;
                }
            }
        }
        return result;
    }

    private void drain(String key) {
        for (;;) {
            Runnable task;
            synchronized (queues) {
                task = queues.get(key).peekFirst();
            }
            task.run();
            synchronized (queues) {
                ArrayDeque<Runnable> queue = queues.get(key);
                queue.removeFirst();
                pending--;
                if (queue.isEmpty()) {
                    queues.remove(key);
                    return;
                }
            }
        }
    }

    public void stopAccepting() {
        synchronized (queues) {
            closed = true;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        stopAccepting();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS))
                workers.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
