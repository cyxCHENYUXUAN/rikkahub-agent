package com.rikkahub.agent.execution;

import java.nio.file.Path;
import java.util.List;

/** Workspace-confined file operations. Implementations decide their own I/O policy. */
public interface FileSystem {
    byte[] read(Path path);

    void write(Path path, byte[] content);

    boolean exists(Path path);

    List<Path> list(Path directory);

    /** Bounded byte range. Unsupported by older adapters, never an unbounded fallback. */
    default byte[] read(Path path, long offset, int maxBytes) {
        throw new UnsupportedOperationException("bounded read unavailable");
    }

    default FileWalk walk(Path directory, int maxDepth, int maxEntries) {
        throw new UnsupportedOperationException("bounded walk unavailable");
    }

    /** Replaces exactly one UTF-8 match using an atomic file replacement. */
    default void edit(Path path, String expected, String replacement) {
        throw new UnsupportedOperationException("atomic edit unavailable");
    }
}
