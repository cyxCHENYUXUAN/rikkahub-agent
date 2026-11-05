package com.rikkahub.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.CapturedOutput;
import com.rikkahub.agent.execution.Command;
import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.execution.FileSystem;
import com.rikkahub.agent.execution.JobInfo;
import com.rikkahub.agent.execution.JobManager;
import com.rikkahub.agent.execution.JobSpec;
import com.rikkahub.agent.execution.ProcessOutput;
import com.rikkahub.agent.execution.ProcessResult;
import com.rikkahub.agent.execution.ProcessRunner;
import com.rikkahub.agent.execution.Terminal;
import com.rikkahub.agent.execution.TerminalHandle;
import com.rikkahub.agent.execution.TerminalSpec;
import com.rikkahub.agent.execution.Workspace;

/**
 * In-memory / tempdir test double for {@link ExecutionEnvironment}.
 * Prevents test runs from executing arbitrary host shell processes or modifying the developer's workstation.
 */
public final class FakeExecutionEnvironment {

    private final Path rootDir;
    private final Workspace workspace;
    private final FileSystem fileSystem;
    private final FakeProcessRunner processRunner;
    private final FakeTerminal terminal;
    private final FakeJobManager jobManager;
    private final ExecutionEnvironment environment;

    public FakeExecutionEnvironment(Path rootDir) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir").toAbsolutePath().normalize();
        this.workspace = new Workspace(this.rootDir);
        this.fileSystem = new SimpleFileSystem(this.rootDir);
        this.processRunner = new FakeProcessRunner();
        this.terminal = new FakeTerminal();
        this.jobManager = new FakeJobManager();
        this.environment = new ExecutionEnvironment(
                workspace,
                fileSystem,
                processRunner,
                terminal,
                jobManager
        );
    }

    public ExecutionEnvironment environment() {
        return environment;
    }

    public Workspace workspace() {
        return workspace;
    }

    public FileSystem fileSystem() {
        return fileSystem;
    }

    public FakeProcessRunner processRunner() {
        return processRunner;
    }

    public FakeTerminal terminal() {
        return terminal;
    }

    public FakeJobManager jobManager() {
        return jobManager;
    }

    public Path rootDir() {
        return rootDir;
    }

    public static final class FakeProcessRunner implements ProcessRunner {
        private final List<Command> executedCommands = new CopyOnWriteArrayList<>();
        private Function<Command, ProcessResult> handler = cmd -> new ProcessResult(
                0,
                "fake stdout for: " + String.join(" ", cmd.argv()),
                "",
                Duration.ofMillis(10)
        );

        @Override
        public ProcessResult run(Command command, CancellationToken signal) {
            Objects.requireNonNull(command, "command");
            executedCommands.add(command);
            if (signal != null && signal.isCancelled()) {
                return new ProcessResult(130, "", "Command cancelled", Duration.ofMillis(1));
            }
            return handler.apply(command);
        }

        public void setHandler(Function<Command, ProcessResult> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        public List<Command> executedCommands() {
            return new ArrayList<>(executedCommands);
        }

        public Command lastCommand() {
            if (executedCommands.isEmpty()) {
                throw new IllegalStateException("No commands were executed");
            }
            return executedCommands.get(executedCommands.size() - 1);
        }
    }

    public static final class FakeTerminal implements Terminal {
        @Override
        public TerminalHandle open(TerminalSpec spec, CancellationToken signal) {
            return new TerminalHandle() {
                @Override
                public void write(String input) {
                }

                @Override
                public void resize(int columns, int rows) {
                    throw new UnsupportedOperationException("Terminal resize unsupported in testkit");
                }

                @Override
                public void close() {
                }
            };
        }
    }

    public static final class FakeJobManager implements JobManager {
        private final AtomicInteger counter = new AtomicInteger();
        private final List<JobInfo> jobs = new CopyOnWriteArrayList<>();

        @Override
        public String submit(JobSpec job) {
            String id = "job-" + counter.incrementAndGet();
            jobs.add(new JobInfo(id, job.name(), "COMPLETED", 0));
            return id;
        }

        @Override
        public List<JobInfo> list() {
            return new ArrayList<>(jobs);
        }

        @Override
        public boolean cancel(String jobId) {
            return true;
        }

        @Override
        public ProcessOutput output(String jobId) {
            return new ProcessOutput(
                    new CapturedOutput("job output", 10, false, null, false),
                    new CapturedOutput("", 0, false, null, false),
                    true,
                    null
            );
        }
    }

    private static final class SimpleFileSystem implements FileSystem {
        private final Path root;

        SimpleFileSystem(Path root) {
            this.root = root;
        }

        @Override
        public byte[] read(Path path) {
            try {
                return Files.readAllBytes(path);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read: " + path, e);
            }
        }

        @Override
        public void write(Path path, byte[] content) {
            try {
                Path parent = path.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Files.write(path, content);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write: " + path, e);
            }
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path);
        }

        @Override
        public List<Path> list(Path directory) {
            try (var stream = Files.list(directory)) {
                return stream.toList();
            } catch (IOException e) {
                throw new RuntimeException("Failed to list: " + directory, e);
            }
        }

        @Override
        public byte[] read(Path path, long offset, int maxBytes) {
            byte[] all = read(path);
            if (offset >= all.length) {
                return new byte[0];
            }
            int length = (int) Math.min(maxBytes, all.length - offset);
            byte[] sub = new byte[length];
            System.arraycopy(all, (int) offset, sub, 0, length);
            return sub;
        }

        @Override
        public void edit(Path path, String expected, String replacement) {
            String text = new String(read(path), StandardCharsets.UTF_8);
            if (!text.contains(expected)) {
                throw new IllegalArgumentException("Expected string not found in " + path);
            }
            String updated = text.replace(expected, replacement);
            write(path, updated.getBytes(StandardCharsets.UTF_8));
        }
    }
}
