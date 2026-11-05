package com.rikkahub.agent.execution;

import java.util.Objects;

/** The five execution services handed to a single agent. */
public record ExecutionEnvironment(Workspace workspace, FileSystem fileSystem,
                                   ProcessRunner processRunner, Terminal terminal,
                                   JobManager jobManager) {
    public ExecutionEnvironment {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(fileSystem, "fileSystem");
        Objects.requireNonNull(processRunner, "processRunner");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(jobManager, "jobManager");
    }
}
