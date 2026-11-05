package com.rikkahub.features.memory.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.rikkahub.agent.api.TurnStopContext;
import com.rikkahub.agent.session.SessionId;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Optional background extraction after a turn; disabled by default to avoid paid calls. */
@Service
public final class MemoryExtractionScheduler implements AutoCloseable {
    private final MemoryExtractionService extraction;
    private final boolean enabled;
    private final ThreadPoolExecutor workers;

    public MemoryExtractionScheduler(MemoryExtractionService extraction,
                                     @Value("${rikkahub.memory.extract-on-turn:false}") boolean enabled) {
        this.extraction = extraction;
        this.enabled = enabled;
        this.workers = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "rikkahub-memory-extract");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
    }

    public void onTurn(TurnStopContext context) {
        if (!enabled) {
            return;
        }
        workers.execute(() -> {
            try {
                extraction.extract(SessionId.of(context.sessionId()));
            } catch (RuntimeException ignored) {
                // Extraction is best effort; the manual endpoint remains available.
            }
        });
    }

    @PreDestroy
    @Override
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
