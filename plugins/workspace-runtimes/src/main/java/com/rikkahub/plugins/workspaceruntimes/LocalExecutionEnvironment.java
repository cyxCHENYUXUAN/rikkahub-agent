package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.execution.Workspace;
import java.nio.file.Path;
import java.util.Objects;

/** One workspace and one resource/opaque-output namespace per session. Not an OS sandbox. */
public final class LocalExecutionEnvironment implements AutoCloseable {
    private final ExecutionEnvironment environment;
    private final LocalOutputStore outputs;
    private final LocalProcessSupervisor supervisor;
    private final LocalTerminal terminal;
    private final LocalJobManager jobs;

    public LocalExecutionEnvironment(Path root, Path spillRoot, RuntimeLimits limits) {
        Objects.requireNonNull(limits);
        Path workspacePath = root.toAbsolutePath().normalize();
        Path outputPath = spillRoot.toAbsolutePath().normalize();
        if (workspacePath.startsWith(outputPath) || outputPath.startsWith(workspacePath))
            throw new IllegalArgumentException("workspace and output roots must not overlap");
        LocalPaths paths = new LocalPaths(workspacePath);
        outputs = new LocalOutputStore(outputPath, limits);
        supervisor = new LocalProcessSupervisor(paths, limits, outputs);
        terminal = new LocalTerminal(supervisor, limits, outputs);
        jobs = new LocalJobManager(supervisor, limits);
        environment = new ExecutionEnvironment(new Workspace(paths.root), new LocalFileSystem(paths, limits),
                new LocalProcessRunner(supervisor), terminal, jobs);
    }
    public LocalExecutionEnvironment(Path root, Path spillRoot) { this(root, spillRoot, RuntimeLimits.defaults()); }
    public ExecutionEnvironment environment() { return environment; }
    public byte[] readOutput(String reference, long offset, int maxBytes) { return outputs.read(reference, offset, maxBytes); }
    @Override public synchronized void close() {
        supervisor.close();
        terminal.close();
        jobs.close();
    }
}
