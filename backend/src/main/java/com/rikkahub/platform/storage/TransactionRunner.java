package com.rikkahub.platform.storage;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Root short transactions. Only SQLite BUSY retries after rollback and connection return. */
@Component
public final class TransactionRunner {
    private final TransactionTemplate template;

    public TransactionRunner(PlatformTransactionManager manager) {
        template = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    public <T> T read(Supplier<T> work) { return write(work); }

    public <T> T write(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("storage root operation cannot join an ambient transaction");
        }
        for (int attempt = 0; ; attempt++) {
            try { return template.execute(status -> work.get()); }
            catch (RuntimeException ex) {
                if (attempt >= 2 || !busy(ex)) { throw ex; }
                try { Thread.sleep(10L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while retrying SQLite BUSY", interrupted);
                }
            }
        }
    }

    private static boolean busy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() & 255) == 5) { return true; }
        }
        return false;
    }
}
