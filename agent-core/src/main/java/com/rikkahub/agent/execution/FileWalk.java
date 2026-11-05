package com.rikkahub.agent.execution;

import java.nio.file.Path;
import java.util.List;

/** Workspace-relative paths; truncated indicates an entry or depth limit was hit. */
public record FileWalk(List<Path> paths, boolean truncated) {
    public FileWalk { paths = List.copyOf(paths); }
}
