package com.rikkahub.agent.execution;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A lexical directory boundary, not an OS sandbox. File adapters must also
 * validate real paths and links before I/O. Every resolved path remains under {@link #root()}.
 */
public record Workspace(Path root) {
    public Workspace {
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
    }

    /**
     * Resolves a path string against the workspace root.
     *
     * @throws IllegalArgumentException if the path contains {@code ..}, is an
     *         absolute path outside the root, or normalizes outside the root
     */
    public Path resolve(String path) {
        Objects.requireNonNull(path, "path");
        Path candidate = Path.of(path);
        for (Path name : candidate) {
            if ("..".equals(name.toString())) {
                throw new IllegalArgumentException("path escapes workspace: " + path);
            }
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        return resolved;
    }
}
