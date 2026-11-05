package com.rikkahub.platform.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DatabaseSnapshotService {
    private final JdbcTemplate jdbc;
    private final StoragePaths paths;

    public DatabaseSnapshotService(JdbcTemplate jdbc, StoragePaths paths) {
        this.jdbc = jdbc;
        this.paths = paths;
    }

    /** Creates a consistent database snapshot; attachment archiving is a separate step. */
    public Path createSnapshot() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Create database snapshots outside a transaction");
        }
        String name = "rikkahub-" + UUID.randomUUID();
        Path temporary = paths.backupsDirectory().resolve(name + ".partial");
        Path snapshot = paths.backupsDirectory().resolve(name + ".db");
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                    statement.setString(1, temporary.toString());
                    statement.execute();
                }
                return null;
            });
            Files.move(temporary, snapshot);
            return snapshot;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
