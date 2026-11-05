package com.rikkahub.features.system.application.dto;

import java.time.Instant;

/**
 * Public metadata summary of an available or newly created backup archive.
 */
public record BackupArchiveInfo(
        String filename,
        long sizeBytes,
        String sha256,
        int totalFiles,
        Instant createdAt
) {}
