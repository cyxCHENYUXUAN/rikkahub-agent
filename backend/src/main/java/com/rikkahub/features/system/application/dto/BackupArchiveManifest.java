package com.rikkahub.features.system.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Metadata manifest included in every .rkpack archive for verification and integrity checking.
 */
public record BackupArchiveManifest(
        String format,
        String version,
        String backupId,
        Instant createdAt,
        String appVersion,
        String redactionPolicy,
        int totalFiles,
        long totalSizeBytes,
        List<ArchiveFileEntry> files
) {
    public static final String FORMAT_NAME = "rikkahub-archive";
    public static final String CURRENT_VERSION = "1.0";
    public static final String REDACTION_CREDENTIALS_EXCLUDED = "CREDENTIALS_EXCLUDED";

    public record ArchiveFileEntry(
            String path,
            long sizeBytes,
            String sha256
    ) {}
}
