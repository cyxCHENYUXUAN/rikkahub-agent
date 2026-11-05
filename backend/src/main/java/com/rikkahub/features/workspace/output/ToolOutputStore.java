package com.rikkahub.features.workspace.output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.sql.DataSource;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.StoragePaths;

/**
 * Durable, session-bound tool output storage under data/tool_outputs. Quotas
 * are charged from the index table so they survive restarts; file identity is
 * verified against the recorded size and hash before any byte is served.
 */
public final class ToolOutputStore implements AutoCloseable {
    /** perOutput, perSessionBytes, totalBytes, perSessionCount, totalCount. */
    public record Limits(int perOutputBytes, long perSessionBytes, long totalBytes,
                         int perSessionCount, int totalCount) {
        public Limits {
            if (perOutputBytes < 1 || perSessionBytes < 1 || totalBytes < 1
                    || perSessionCount < 1 || totalCount < 1) {
                throw new IllegalArgumentException("output limits must be positive");
            }
        }
    }

    /** Host-provided bounded read; implementations may fail or return partial chunks. */
    public interface BoundedReader {
        byte[] read(String reference, long offset, int maxBytes) throws Exception;
    }

    public record Publication(String reference, String status, long retainedBytes, long sourceBytes) {}

    public record Range(byte[] bytes, long totalBytes, boolean truncated, String status) {}

    public record OutputSummary(String reference, long retainedBytes, long sourceBytes,
                                String status, boolean available) {}

    public record Usage(long totalBytes, long totalLimitBytes, long sessionBytes,
                        long sessionLimitBytes, List<OutputSummary> outputs) {}

    static final int MAX_READ = 262144;
    private static final String HEX = "0123456789abcdef";
    private final Path root;
    private final DataSource db;
    private final Limits limits;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean closed;

    public ToolOutputStore(StoragePaths paths, DataSource db, Limits limits) throws IOException {
        this.db = db;
        this.limits = limits;
        this.root = paths.dataDirectory().resolve("tool_outputs");
        Files.createDirectories(root);
        try {
            requireRealDirectory(root);
        } catch (IOException redirected) {
            throw new IllegalStateException("tool output storage root is not a real directory", redirected);
        }
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (isReparsePoint(directory)) {
            throw new IOException("tool output storage root must be a real directory, not a link");
        }
    }

    /** Windows junction/mount points must never redirect durable output storage. */
    private static boolean isReparsePoint(Path path) {
        int attributes = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (attributes == com.sun.jna.platform.win32.WinBase.INVALID_FILE_ATTRIBUTES) return false;
        return (attributes & 0x400) != 0;
    }

    public Publication spill(SessionId session, byte[] content) {
        var lock = lifecycle.readLock(); lock.lock();
        try {
            requireOpen();
            requireSession(session);
            Quota quota = quota(session);
            long room = Math.max(0, Math.min(Math.min(quota.bytesRemaining(), quota.sessionBytesRemaining()),
                    limits.perOutputBytes()));
            int retained = (int) Math.min(content.length, room);
            if ((retained == 0 && content.length > 0)
                    || Math.min(quota.countRemaining(), quota.sessionCountRemaining()) == 0) {
                return new Publication(null, "QUOTA_REACHED", 0, content.length);
            }
            String status = retained < content.length ? "QUOTA_REACHED" : "COMPLETE";
            return publish(session, firstOf(content, retained), content.length, status);
        } finally { lock.unlock(); }
    }

    /**
     * Promotes caller-opaque runtime output into durable storage. The reader is
     * invoked only while session quota remains; a crash leaves a charged PENDING
     * row that is never downloadable, so restart never replays the source read.
     */
    public Publication promote(SessionId session, String reference, BoundedReader reader) {
        var lock = lifecycle.readLock(); lock.lock();
        try {
            requireOpen();
            validateReference(reference);
            requireSession(session);
            Quota quota = quota(session);
            long room = Math.max(0, Math.min(Math.min(quota.bytesRemaining(), quota.sessionBytesRemaining()),
                    limits.perOutputBytes()));
            if (room <= 0 || Math.min(quota.countRemaining(), quota.sessionCountRemaining()) == 0) {
                return new Publication(null, "QUOTA_REACHED", 0, -1);
            }
            return drain(session, reference, reader, room, quota);
        } catch (ApiFailure | IllegalArgumentException | IllegalStateException contract) {
            throw contract;
        } catch (Exception failure) {
            return new Publication(null, "SOURCE_FAILED", 0, -1);
        } finally { lock.unlock(); }
    }

    private Publication drain(SessionId session, String reference, BoundedReader reader,
                              long room, Quota quota) throws Exception {
        java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
        long offset = 0;
        boolean sourceFailed = false;
        while (kept.size() < room) {
            int max = (int) Math.min(room - kept.size(), MAX_READ);
            byte[] chunk;
            try {
                chunk = reader.read(reference, offset, max);
            } catch (Exception failure) {
                sourceFailed = true;
                break;
            }
            if (chunk == null || chunk.length > max) {
                sourceFailed = true;
                kept.reset();
                break;
            }
            if (chunk.length == 0) break;
            kept.write(chunk, 0, chunk.length);
            offset += chunk.length;
        }
        int retained = kept.size();
        if (retained == 0) {
            return new Publication(null, sourceFailed ? "SOURCE_FAILED" : "COMPLETE", 0, -1);
        }
        String status = sourceFailed ? "SOURCE_FAILED" : kept.size() >= room ? "QUOTA_REACHED" : "COMPLETE";
        long sourceBytes = "COMPLETE".equals(status) ? offset : -1;
        return publish(session, kept.toByteArray(), sourceBytes, status);
    }

    private Publication publish(SessionId session, byte[] content, long sourceBytes, String status) {
        String reference = newReference();
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO tool_outputs(reference,session_id,state,charged_bytes,retained_bytes,source_bytes,status,created_at)"
                        + " VALUES(?,?,'PENDING',?,?,?,'COMPUTING',?)")) {
                    insert.setString(1, reference);
                    insert.setString(2, session.value());
                    insert.setLong(3, content.length);
                    insert.setLong(4, content.length);
                    insert.setLong(5, sourceBytes);
                    insert.setString(6, EventCodec.timestamp(Instant.now()));
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("tool output index unavailable", failure);
        }
        Path file = root.resolve(reference);
        try {
            if (isReparsePoint(root)) throw new IllegalStateException("tool output root became a link");
            Files.write(file, content);
            try (Connection connection = db.getConnection()) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE tool_outputs SET state='READY',status=?,content_hash=? WHERE reference=?")) {
                    update.setString(1, status);
                    update.setString(2, hash(content));
                    update.setString(3, reference);
                    update.executeUpdate();
                }
            }
            return new Publication(reference, status, content.length, sourceBytes);
        } catch (RuntimeException | IOException | SQLException failure) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { /* best effort */ }
            try (Connection connection = db.getConnection(); PreparedStatement remove =
                    connection.prepareStatement("DELETE FROM tool_outputs WHERE reference=? AND state='PENDING'")) {
                remove.setString(1, reference);
                remove.executeUpdate();
            } catch (SQLException ignored) { /* quota stays charged on index failure; fail closed */ }
            return new Publication(null, "STORAGE_FAILED", 0, sourceBytes);
        }
    }

    public Usage usage(SessionId session) {
        var lock = lifecycle.readLock();
        lock.lock();
        try {
            requireOpen();
            requireSession(session);
            Quota quota = quota(session);
            List<OutputSummary> entries = new ArrayList<>();
            try (Connection connection = db.getConnection();
                 PreparedStatement query = connection.prepareStatement("""
                         SELECT reference,retained_bytes,source_bytes,status FROM tool_outputs
                         WHERE session_id=? AND state='READY' ORDER BY created_at DESC,reference
                         """)) {
                query.setString(1, session.value());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String reference = rows.getString("reference");
                        entries.add(new OutputSummary(reference, rows.getLong("retained_bytes"),
                                rows.getLong("source_bytes"), rows.getString("status"), hasFile(reference)));
                    }
                }
            }
            return new Usage(limits.totalBytes() - quota.bytesRemaining(), limits.totalBytes(),
                    limits.perSessionBytes() - quota.sessionBytesRemaining(), limits.perSessionBytes(),
                    List.copyOf(entries));
        } catch (SQLException failure) {
            throw new IllegalStateException("tool output index unavailable", failure);
        } finally {
            lock.unlock();
        }
    }

    /** Called before workers start, while the data-directory instance lock is held. */
    void recoverPending() throws IOException {
        requireRealDirectory(root);
        try (Connection connection = db.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT reference FROM tool_outputs WHERE state='PENDING'");
             PreparedStatement remove = connection.prepareStatement(
                     "DELETE FROM tool_outputs WHERE reference=? AND state='PENDING'")) {
            List<String> pending = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    pending.add(rows.getString(1));
                }
            }
            for (String reference : pending) {
                if (!validReference(reference)) {
                    continue;
                }
                Path file = root.resolve(reference);
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) || isReparsePoint(file)) {
                    continue;
                }
                Files.deleteIfExists(file);
                remove.setString(1, reference);
                remove.executeUpdate();
            }
        } catch (SQLException failure) {
            throw new IOException("Could not recover unfinished tool outputs", failure);
        }
    }

    private static boolean validReference(String reference) {
        return reference != null && reference.matches("out_[0-9a-f]{32}");
    }

    private boolean hasFile(String reference) {
        if (!validReference(reference)) {
            return false;
        }
        Path file = root.resolve(reference);
        return !isReparsePoint(root) && !isReparsePoint(file)
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    public Range read(SessionId session, String reference, long offset, int limit) {
        var lock = lifecycle.readLock(); lock.lock();
        try {
            requireOpen();
            if (!validReference(reference)) {
                throw new IllegalArgumentException("invalid tool output reference");
            }
            if (offset < 0 || limit < 0 || limit > MAX_READ || offset + limit < 0) {
                throw new IllegalArgumentException("invalid tool output range");
            }
            IndexRow row = row(session, reference);
            if (row == null || !"READY".equals(row.state())) {
                throw new ApiFailure(404, "TOOL_OUTPUT_NOT_FOUND", "The tool output does not exist.");
            }
            if (!hasFile(reference)) {
                throw new ApiFailure(503, "TOOL_OUTPUT_UNAVAILABLE", "The tool output file is missing or unavailable.");
            }
            Path file = root.resolve(reference);
            byte[] stored;
            try {
                stored = Files.readAllBytes(file);
            } catch (IOException unavailable) {
                throw new ApiFailure(503, "TOOL_OUTPUT_UNAVAILABLE", "The tool output could not be read.");
            }
            if (stored.length != row.retainedBytes() || !hash(stored).equals(row.contentHash())) {
                throw new ApiFailure(503, "TOOL_OUTPUT_UNAVAILABLE", "The tool output identity changed on disk.");
            }
            long from = Math.min(offset, stored.length);
            int end = (int) Math.min(from + limit, stored.length);
            byte[] bytes = java.util.Arrays.copyOfRange(stored, (int) from, end);
            // The truncated flag describes the stored source, not this range: a range
            // smaller than the total is ordinary pagination.
            boolean truncated = !"COMPLETE".equals(row.status());
            return new Range(bytes, stored.length, truncated, row.status());
        } finally { lock.unlock(); }
    }

    @Override public void close() {
        var lock = lifecycle.writeLock(); lock.lock();
        try { closed = true; } finally { lock.unlock(); }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("tool output store is closed");
    }

    private void requireSession(SessionId session) {
        try (Connection connection = db.getConnection(); PreparedStatement found = connection.prepareStatement(
                "SELECT 1 FROM agent_sessions WHERE id=?")) {
            found.setString(1, session.value());
            try (ResultSet rows = found.executeQuery()) {
                if (!rows.next()) {
                    throw new ApiFailure(404, "TOOL_OUTPUT_NOT_FOUND", "The tool output does not exist.");
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("tool output index unavailable", failure);
        }
    }

    private static void validateReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 256
                || reference.indexOf('/') >= 0 || reference.indexOf('\\') >= 0
                || reference.indexOf(':') >= 0 || reference.indexOf(0) >= 0
                || reference.equals(".") || reference.equals("..")) {
            throw new IllegalArgumentException("invalid tool output reference");
        }
    }

    private Quota quota(SessionId session) {
        try (Connection connection = db.getConnection();
             PreparedStatement totals = connection.prepareStatement(
                     "SELECT coalesce(sum(charged_bytes),0), count(*) FROM tool_outputs");
             PreparedStatement owned = connection.prepareStatement(
                     "SELECT coalesce(sum(charged_bytes),0), count(*) FROM tool_outputs WHERE session_id=?")) {
            long bytesUsed = 0; long countUsed = 0; long sessionBytes = 0; long sessionCount = 0;
            try (ResultSet rows = totals.executeQuery()) {
                if (rows.next()) { bytesUsed = rows.getLong(1); countUsed = rows.getLong(2); }
            }
            owned.setString(1, session.value());
            try (ResultSet rows = owned.executeQuery()) {
                if (rows.next()) { sessionBytes = rows.getLong(1); sessionCount = rows.getLong(2); }
            }
            return new Quota(limits.totalBytes() - bytesUsed, limits.totalCount() - countUsed,
                    limits.perSessionBytes() - sessionBytes, limits.perSessionCount() - sessionCount);
        } catch (SQLException failure) {
            throw new IllegalStateException("tool output index unavailable", failure);
        }
    }

    private IndexRow row(SessionId session, String reference) {
        try (Connection connection = db.getConnection(); PreparedStatement found = connection.prepareStatement(
                "SELECT state,retained_bytes,status,content_hash FROM tool_outputs WHERE reference=? AND session_id=?")) {
            found.setString(1, reference);
            found.setString(2, session.value());
            try (ResultSet rows = found.executeQuery()) {
                if (!rows.next()) return null;
                return new IndexRow(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("tool output index unavailable", failure);
        }
    }

    private record Quota(long bytesRemaining, long countRemaining,
                         long sessionBytesRemaining, long sessionCountRemaining) {}

    private record IndexRow(String state, long retainedBytes, String status, String contentHash) {}

    private static String newReference() {
        StringBuilder out = new StringBuilder("out_");
        for (int i = 0; i < 16; i++) {
            int value = java.util.concurrent.ThreadLocalRandom.current().nextInt(256);
            out.append(HEX.charAt(value >>> 4)).append(HEX.charAt(value & 15));
        }
        return out.toString();
    }

    private static byte[] firstOf(byte[] content, int retained) {
        return retained == content.length ? content : java.util.Arrays.copyOf(content, retained);
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
