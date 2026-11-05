package com.rikkahub.features.system.application.dto;

import java.time.Instant;

/**
 * An isolated restore directory ready to use on the next startup.
 */
public record RestoreResult(
        boolean success,
        String backupId,
        Instant backupCreatedAt,
        int restoredFiles,
        long totalSizeBytes,
        String dataDirectory
) {}
