package com.rikkahub.features.system.application;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.rikkahub.features.system.application.dto.BackupArchiveInfo;
import com.rikkahub.features.system.application.dto.BackupArchiveManifest;
import com.rikkahub.features.system.application.dto.RestoreResult;
import com.rikkahub.platform.storage.DatabaseSnapshotService;
import com.rikkahub.platform.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Creates local archives and prepares verified data directories for restart. */
@Service
public class BackupRestoreService {
    private static final Logger LOG = LoggerFactory.getLogger(BackupRestoreService.class);
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Set<String> ALLOWED_TOP_DIRECTORIES = Set.of(
            "database", "memory", "skills", "workspace", "tool_outputs"
    );

    private final StoragePaths paths;
    private final DatabaseSnapshotService databaseSnapshotService;
    private final ObjectMapper mapper;

    public BackupRestoreService(StoragePaths paths, DatabaseSnapshotService databaseSnapshotService,
                                ObjectMapper mapper) {
        this.paths = paths;
        this.databaseSnapshotService = databaseSnapshotService;
        this.mapper = mapper;
    }

    /**
     * Archives a SQLite snapshot and its durable outputs. User-editable directories are copied separately.
     */
    public BackupArchiveInfo createBackup() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cannot create backup snapshot while a transaction is active");
        }

        Instant now = Instant.now();
        String backupId = UUID.randomUUID().toString();
        String timestampStr = FILE_TIMESTAMP_FORMATTER.format(now);
        String filename = "rikkahub-backup-" + timestampStr + "-" + backupId.substring(0, 8) + ".rkpack";

        Path backupsDir = paths.backupsDirectory();
        Files.createDirectories(backupsDir);
        Path partialFile = backupsDir.resolve(filename + ".partial");
        Path finalFile = backupsDir.resolve(filename);

        Path dbSnapshot = null;
        try {
            // Step 1: Create atomic consistent snapshot of database
            dbSnapshot = databaseSnapshotService.createSnapshot();

            // Step 2: Collect file entries and stream into ZIP
            List<BackupArchiveManifest.ArchiveFileEntry> manifestEntries = new ArrayList<>();
            long totalUncompressedBytes = 0;

            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partialFile)))) {
                var database = writeZipEntry(zos, "database/rikkahub.db", dbSnapshot);
                manifestEntries.add(database);
                totalUncompressedBytes += database.sizeBytes();

                // 2) Write user directories: memory, skills, workspace, tool_outputs
                totalUncompressedBytes += archiveDirectory(zos, paths.memoryDirectory(), "memory", manifestEntries);
                totalUncompressedBytes += archiveDirectory(zos, paths.skillsDirectory(), "skills", manifestEntries);
                totalUncompressedBytes += archiveDirectory(zos, paths.workspaceDirectory(), "workspace", manifestEntries);
                totalUncompressedBytes += archiveToolOutputs(zos, dbSnapshot, manifestEntries);

                // 3) Create and serialize manifest.json
                BackupArchiveManifest manifest = new BackupArchiveManifest(
                        BackupArchiveManifest.FORMAT_NAME,
                        BackupArchiveManifest.CURRENT_VERSION,
                        backupId,
                        now,
                        "1.0-SNAPSHOT",
                        BackupArchiveManifest.REDACTION_CREDENTIALS_EXCLUDED,
                        manifestEntries.size(),
                        totalUncompressedBytes,
                        manifestEntries
                );

                byte[] manifestBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
                ZipEntry manifestEntry = new ZipEntry("manifest.json");
                manifestEntry.setTime(now.toEpochMilli());
                zos.putNextEntry(manifestEntry);
                zos.write(manifestBytes);
                zos.closeEntry();
            }

            Files.move(partialFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            long archiveSize = Files.size(finalFile);
            String archiveSha256 = computeSha256(finalFile);

            LOG.info("Full backup archive created: {} (size: {} bytes, {} files)",
                    filename, archiveSize, manifestEntries.size());

            return new BackupArchiveInfo(filename, archiveSize, archiveSha256, manifestEntries.size(), now);
        } finally {
            Files.deleteIfExists(partialFile);
            if (dbSnapshot != null) {
                Files.deleteIfExists(dbSnapshot);
            }
        }
    }

    /**
     * Lists all available local .rkpack backups sorted by creation time descending.
     */
    public List<BackupArchiveInfo> listBackups() {
        Path backupsDir = paths.backupsDirectory();
        if (!Files.isDirectory(backupsDir)) {
            return List.of();
        }

        List<BackupArchiveInfo> result = new ArrayList<>();
        try (var stream = Files.list(backupsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".rkpack"))
                    .forEach(file -> {
                        try {
                            BackupArchiveManifest manifest = readManifest(file);
                            if (manifest != null) {
                                String sha256 = computeSha256(file);
                                result.add(new BackupArchiveInfo(
                                        file.getFileName().toString(),
                                        Files.size(file),
                                        sha256,
                                        manifest.totalFiles(),
                                        manifest.createdAt()
                                ));
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to read manifest for backup file {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Failed to list backups: {}", e.getMessage());
        }

        result.sort(Comparator.comparing(BackupArchiveInfo::createdAt).reversed());
        return result;
    }

    /**
     * Returns the physical path of a specified backup file after verifying filename safety.
     */
    public Path getBackupFile(String filename) throws FileNotFoundException {
        validateFilename(filename);
        Path target = paths.backupsDirectory().resolve(filename).normalize();
        if (!target.startsWith(paths.backupsDirectory()) || !Files.isRegularFile(target)) {
            throw new FileNotFoundException("Backup archive not found: " + filename);
        }
        return target;
    }

    /**
     * Restores system from a local backup file name.
     */
    public RestoreResult restoreFromLocalBackup(String filename) throws IOException {
        Path backupFile = getBackupFile(filename);
        return restore(backupFile);
    }

    /** Prepares a separate data directory. Normal startup repairs interrupted work. */
    public RestoreResult restore(Path archivePath) throws IOException {
        if (!Files.isRegularFile(archivePath)) {
            throw new FileNotFoundException("Backup archive not found: " + archivePath);
        }

        BackupArchiveManifest manifest = readManifest(archivePath);
        if (manifest == null || !BackupArchiveManifest.FORMAT_NAME.equals(manifest.format())
                || !BackupArchiveManifest.CURRENT_VERSION.equals(manifest.version()) || manifest.files() == null) {
            throw new IllegalArgumentException("Invalid backup manifest or unsupported format");
        }
        Path backupRoot = paths.backupsDirectory();
        Path destination = backupRoot.resolve("restored-" + UUID.randomUUID());
        Path staging = Files.createTempDirectory(backupRoot, ".staging-");
        try {
            var remaining = new HashMap<Path, BackupArchiveManifest.ArchiveFileEntry>();
            for (var file : manifest.files()) {
                Path target = archiveTarget(staging, file.path());
                if (remaining.putIfAbsent(target, file) != null) {
                    throw new SecurityException("Duplicate file in backup manifest");
                }
            }
            Path database = new StoragePaths(staging, false).databaseFile();
            if (!remaining.containsKey(database)) {
                throw new SecurityException("Missing database in backup manifest");
            }

            try (ZipFile zip = new ZipFile(archivePath.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || "manifest.json".equals(entry.getName())) {
                        continue;
                    }
                    Path target = archiveTarget(staging, entry.getName());
                    var expected = remaining.remove(target);
                    if (expected == null || expected.sizeBytes() != entry.getSize()) {
                        throw new SecurityException("Unexpected backup entry: " + entry.getName());
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream input = zip.getInputStream(entry)) {
                        Files.copy(input, target);
                    }
                    if (Files.size(target) != expected.sizeBytes()
                            || !computeSha256(target).equalsIgnoreCase(expected.sha256())) {
                        throw new SecurityException("Corrupted backup entry: " + entry.getName());
                    }
                }
            }
            if (!remaining.isEmpty()) {
                throw new SecurityException("Backup is missing manifest entries");
            }

            prepareRestoredDatabase(database, destination.resolve("workspace"));
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            return new RestoreResult(true, manifest.backupId(), manifest.createdAt(),
                    manifest.files().size(), manifest.totalSizeBytes(), destination.toString());
        } finally {
            deleteDirectoryRecursively(staging);
        }
    }

    private static Path archiveTarget(Path staging, String entryName) {
        validateZipEntryPath(entryName);
        Path target = staging.resolve(entryName).normalize();
        if (!target.startsWith(staging)
                || !staging.relativize(target).toString().replace('\\', '/').equals(entryName)) {
            throw new SecurityException("Invalid backup path: " + entryName);
        }
        return target;
    }

    private static void prepareRestoredDatabase(Path database, Path workspace) throws IOException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var check = statement.executeQuery("PRAGMA quick_check")) {
                if (!check.next() || !"ok".equals(check.getString(1))) {
                    throw new IOException("Backup database failed integrity validation");
                }
            }
            // Rebase only the exact directory shapes created by the host. Legacy custom
            // working directories retain their recorded paths.
            try (var rows = statement.executeQuery("""
                    SELECT id,cwd,origin,json_extract(header_metadata_json,'$.ticketId') AS ticket,
                           json_extract(header_metadata_json,'$.evalId') AS evaluation
                    FROM agent_sessions
                    """);
                 var update = connection.prepareStatement("UPDATE agent_sessions SET cwd=? WHERE id=?")) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    String origin = java.util.Objects.toString(rows.getString("origin"), "");
                    String key = switch (origin) {
                        case "chat" -> id;
                        case "worker" -> rows.getString("ticket");
                        case "eval" -> rows.getString("evaluation");
                        default -> null;
                    };
                    if (key == null || !key.matches("[A-Za-z0-9_-]+")) {
                        continue;
                    }
                    String relative = switch (origin) {
                        case "worker" -> "tickets/" + key;
                        case "eval" -> "evaluations/" + key;
                        default -> key;
                    };
                    if (rows.getString("cwd").replace('\\', '/').endsWith("/workspace/" + relative)) {
                        update.setString(1, workspace.resolve(relative).toString());
                        update.setString(2, id);
                        update.addBatch();
                    }
                }
                update.executeBatch();
            }
        } catch (SQLException failure) {
            throw new IOException("Backup database could not be prepared", failure);
        }
    }

    private long archiveDirectory(ZipOutputStream zos,
                                  Path sourceDir,
                                  String rootPrefix,
                                  List<BackupArchiveManifest.ArchiveFileEntry> entries) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }

        final long[] bytesArchived = {0};
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = file.getFileName().toString();
                // Skip hidden files, system files, lock files, and temporary backup remnants
                if (fileName.startsWith(".") || fileName.endsWith(".lck") || fileName.endsWith(".partial")
                        || fileName.endsWith(".tmp") || fileName.endsWith("-wal") || fileName.endsWith("-shm")) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = sourceDir.relativize(file);
                String entryPath = rootPrefix + "/" + relative.toString().replace('\\', '/');
                var entry = writeZipEntry(zos, entryPath, file);
                entries.add(entry);
                bytesArchived[0] += entry.sizeBytes();

                return FileVisitResult.CONTINUE;
            }
        });

        return bytesArchived[0];
    }

    private long archiveToolOutputs(ZipOutputStream zip, Path database,
                                    List<BackupArchiveManifest.ArchiveFileEntry> entries) throws IOException {
        long total = 0;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT reference,retained_bytes,content_hash FROM tool_outputs WHERE state='READY'
                     """)) {
            while (rows.next()) {
                String reference = rows.getString("reference");
                if (!reference.matches("out_[0-9a-f]{32}")) {
                    throw new IOException("Invalid output reference in snapshot");
                }
                Path file = paths.toolOutputsDirectory().resolve(reference);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Tool output is missing: " + reference);
                }
                var entry = writeZipEntry(zip, "tool_outputs/" + reference, file);
                if (entry.sizeBytes() != rows.getLong("retained_bytes")
                        || !entry.sha256().equals(rows.getString("content_hash"))) {
                    throw new IOException("Tool output changed on disk: " + reference);
                }
                entries.add(entry);
                total += entry.sizeBytes();
            }
        } catch (SQLException failure) {
            throw new IOException("Could not read the tool output index from the backup snapshot", failure);
        }
        return total;
    }

    private BackupArchiveManifest.ArchiveFileEntry writeZipEntry(ZipOutputStream zip, String name, Path file)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zip.putNextEntry(entry);
        MessageDigest digest = sha256();
        long size;
        try (var input = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), digest)) {
            size = input.transferTo(zip);
        }
        zip.closeEntry();
        return new BackupArchiveManifest.ArchiveFileEntry(name, size, HexFormat.of().formatHex(digest.digest()));
    }

    private BackupArchiveManifest readManifest(Path archiveFile) {
        try (ZipFile zip = new ZipFile(archiveFile.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null) {
                return null;
            }
            try (InputStream is = zip.getInputStream(manifestEntry)) {
                return mapper.readValue(is, BackupArchiveManifest.class);
            }
        } catch (Exception e) {
            LOG.warn("Failed to read manifest from {}: {}", archiveFile, e.getMessage());
            return null;
        }
    }

    private static String computeSha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 not available", failure);
        }
    }

    private static void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Backup filename must not be blank");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Illegal path traversal sequence in filename: " + filename);
        }
        if (!filename.endsWith(".rkpack")) {
            throw new IllegalArgumentException("Filename must end with .rkpack: " + filename);
        }
    }

    private static void validateZipEntryPath(String entryName) {
        if (entryName.startsWith("/") || entryName.contains("\\") || entryName.contains(":")
                || List.of(entryName.split("/", -1)).contains("..")) {
            throw new SecurityException("Illegal path traversal sequence in zip entry: " + entryName);
        }
        int firstSlash = entryName.indexOf('/');
        String topDir = firstSlash > 0 ? entryName.substring(0, firstSlash) : entryName;
        if (!ALLOWED_TOP_DIRECTORIES.contains(topDir)
                || ("database".equals(topDir) && !"database/rikkahub.db".equals(entryName))) {
            throw new SecurityException("Zip entry contains unauthorized top-level directory: " + topDir);
        }
    }

    private static void deleteDirectoryRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
