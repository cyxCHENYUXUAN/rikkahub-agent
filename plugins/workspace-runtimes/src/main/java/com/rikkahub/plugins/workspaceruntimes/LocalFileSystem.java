package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.execution.FileSystem;
import com.rikkahub.agent.execution.FileWalk;
import com.rikkahub.agent.execution.Workspace;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Path;
import java.util.*;

/** Bounded I/O exclusively through pinned directory and file handles. */
public final class LocalFileSystem implements FileSystem {
    private final LocalPaths paths;
    private final RuntimeLimits limits;
    public LocalFileSystem(Workspace workspace) { this(workspace, RuntimeLimits.defaults()); }
    public LocalFileSystem(Workspace workspace, RuntimeLimits limits) { this(new LocalPaths(workspace.root()), limits); }
    LocalFileSystem(LocalPaths paths, RuntimeLimits limits) { this.paths = paths; this.limits = Objects.requireNonNull(limits); }

    @Override public synchronized byte[] read(Path path) {
        Path file = paths.resolve(path);
        try (var lease = paths.parentLease(file, false);
             var opened = lease.directory().open(file.getFileName().toString(), WindowsFile.READ, WindowsFile.OPEN, false, false)) {
            if (opened.size() > limits.maxFileBytes()) throw new IllegalArgumentException("file exceeds read limit; use a bounded range");
            return opened.read(0, limits.maxFileBytes());
        }
    }
    @Override public synchronized byte[] read(Path path, long offset, int maxBytes) {
        if (offset < 0 || maxBytes < 0 || maxBytes > limits.maxFileBytes()) throw new IllegalArgumentException("invalid read range");
        Path file = paths.resolve(path);
        try (var lease = paths.parentLease(file, false);
             var opened = lease.directory().open(file.getFileName().toString(), WindowsFile.READ, WindowsFile.OPEN, false, false)) {
            return opened.read(offset, maxBytes);
        }
    }
    @Override public synchronized void write(Path path, byte[] content) {
        Objects.requireNonNull(content);
        if (content.length > limits.maxFileBytes()) throw new IllegalArgumentException("write exceeds limit");
        Path file = paths.resolve(path);
        byte[] copy = content.clone();
        try (var lease = paths.parentLease(file, true)) {
            replace(lease.directory(), file.getFileName().toString(), copy, null);
        }
    }
    private void replace(WindowsFile directory, String name, byte[] content, byte[] expected) {
        // Reject an existing directory/link before writing. Replacing a subsequently swapped
        // final entry is a same-directory rename of our bound temp, never a traversal of it.
        try (var existing = directory.openAny(name, true)) {
            if (existing != null) existing.requireRegularFile();
        }
        try (var temporary = directory.temporary()) {
            temporary.file.write(content, 0, content.length);
            if (expected != null) {
                try (var current = directory.open(name, WindowsFile.READ, WindowsFile.OPEN, false, false)) {
                    if (current.size() > limits.maxFileBytes()
                            || !Arrays.equals(expected, current.read(0, limits.maxFileBytes())))
                        throw new IllegalStateException("file changed during edit");
                }
            }
            temporary.publish(name);
        }
    }
    @Override public synchronized void edit(Path path, String expected, String replacement) {
        Objects.requireNonNull(expected); Objects.requireNonNull(replacement);
        if (expected.isEmpty()) throw new IllegalArgumentException("empty edit match");
        if (replacement.length() > limits.maxFileBytes()) throw new IllegalArgumentException("edit exceeds limit");
        Path file = paths.resolve(path);
        try (var lease = paths.parentLease(file, false)) {
            byte[] before;
            try (var original = lease.directory().open(file.getFileName().toString(), WindowsFile.READ, WindowsFile.OPEN, false, false)) {
                if (original.size() > limits.maxFileBytes()) throw new IllegalArgumentException("file exceeds read limit");
                before = original.read(0, limits.maxFileBytes());
            }
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(before)).toString(); }
            catch (CharacterCodingException e) { throw new IllegalArgumentException("edit requires UTF-8"); }
            int start = text.indexOf(expected);
            if (start < 0 || text.indexOf(expected, start + 1) >= 0) throw new IllegalArgumentException("edit requires exactly one match");
            byte[] after = (text.substring(0, start) + replacement + text.substring(start + expected.length())).getBytes(StandardCharsets.UTF_8);
            if (after.length > limits.maxFileBytes()) throw new IllegalArgumentException("edit exceeds limit");
            replace(lease.directory(), file.getFileName().toString(), after, before);
        }
    }
    @Override public synchronized boolean exists(Path path) {
        Path file = paths.resolve(path);
        try {
            if (file.equals(paths.root)) { try (var lease = paths.lease(file)) { return true; } }
            try (var lease = paths.parentLease(file, false); var opened = lease.directory().openAny(file.getFileName().toString(), true)) {
                return opened != null;
            }
        } catch (WindowsFile.NativeFailure e) { if (e.missing()) return false; throw e; }
    }
    @Override public synchronized List<Path> list(Path directory) {
        Path start = paths.resolve(directory);
        ArrayList<Path> result = new ArrayList<>();
        try (var lease = paths.lease(start)) {
            lease.directory().entries((name, isDirectory) -> {
                try (var child = lease.directory().open(name, WindowsFile.READ, WindowsFile.OPEN, isDirectory, false)) {
                    if (result.size() == limits.maxWalkEntries()) throw new IllegalArgumentException("directory listing exceeds limit; use walk");
                    result.add(paths.root.relativize(start.resolve(name))); return true;
                }
            });
        }
        return List.copyOf(result);
    }
    @Override public synchronized FileWalk walk(Path directory, int maxDepth, int maxEntries) {
        if (maxDepth < 1 || maxDepth > limits.maxWalkDepth() || maxEntries < 1 || maxEntries > limits.maxWalkEntries())
            throw new IllegalArgumentException("invalid walk limits");
        Path start = paths.resolve(directory);
        ArrayList<Path> result = new ArrayList<>(); boolean[] truncated = {false};
        try (var lease = paths.lease(start)) { walkDirectory(lease.directory(), start, maxDepth, maxEntries, result, truncated); }
        return new FileWalk(result, truncated[0]);
    }
    private void walkDirectory(WindowsFile directory, Path path, int depth, int max, List<Path> result, boolean[] truncated) {
        directory.entries((name, isDirectory) -> {
            if (result.size() == max) { truncated[0] = true; return false; }
            try (var child = directory.open(name, WindowsFile.READ, WindowsFile.OPEN, isDirectory, false)) {
                Path childPath = path.resolve(name);
                result.add(paths.root.relativize(childPath));
                if (isDirectory) {
                    if (depth == 1) truncated[0] = true;
                    else walkDirectory(child, childPath, depth - 1, max, result, truncated);
                }
                return !(result.size() == max && truncated[0]);
            }
        });
    }
}
