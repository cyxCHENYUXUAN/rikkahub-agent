package com.rikkahub.plugins.workspaceruntimes.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.CapturedOutput;
import com.rikkahub.agent.execution.Command;
import com.rikkahub.agent.execution.ProcessOutput;
import com.rikkahub.agent.execution.ProcessResult;
import com.rikkahub.plugins.workspaceruntimes.LocalOutputStore;
import com.rikkahub.plugins.workspaceruntimes.RuntimeLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxed command execution runner using lightweight container isolation (Docker / Podman).
 * Restricts file access strictly to workspace directory, enforces cpu/memory limits,
 * cuts off network access by default, and guarantees graceful cleanup on cancellation or timeout.
 */
public final class ContainerSandboxRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ContainerSandboxRunner.class);

    private final SandboxConfig config;
    private final String engine;
    private final Path workspaceRoot;
    private final Path spillRoot;
    private final LocalOutputStore outputStore;
    private final RuntimeLimits limits;

    public ContainerSandboxRunner(SandboxConfig config,
                                  String engine,
                                  Path workspaceRoot,
                                  Path spillRoot,
                                  LocalOutputStore outputStore,
                                  RuntimeLimits limits) {
        this.config = config;
        this.engine = engine;
        this.workspaceRoot = workspaceRoot;
        this.spillRoot = spillRoot;
        this.outputStore = outputStore;
        this.limits = limits;
    }

    public ProcessResult run(Command command, CancellationToken token) {
        token.throwIfCancelled();
        String containerName = "rikkahub-box-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fullCommand = buildContainerCommand(command, containerName);
        LOG.debug("Starting containerized sandboxed command in {}: {}", engine, fullCommand);

        long startNanos = System.nanoTime();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            pb.redirectErrorStream(false);
            process = pb.start();

            Process procRef = process;
            token.onCancel(() -> {
                LOG.info("Sandboxed container {} cancelled, killing container", containerName);
                destroyContainer(containerName);
                procRef.destroyForcibly();
            });

            // Capture output streams
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();

            Thread outThread = new Thread(() -> readStream(procRef.getInputStream(), stdoutBuf), "box-stdout");
            Thread errThread = new Thread(() -> readStream(procRef.getErrorStream(), stderrBuf), "box-stderr");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            Duration timeout = command.timeout();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsedNanos = System.nanoTime() - startNanos;
            if (!finished) {
                LOG.warn("Sandboxed container {} timed out after {}s", containerName, timeout.toSeconds());
                destroyContainer(containerName);
                process.destroyForcibly();
                return new ProcessResult(
                        -1,
                        stdoutBuf.toString(),
                        stderrBuf.toString(),
                        Duration.ofNanos(elapsedNanos),
                        new ProcessOutput(
                                new CapturedOutput(stdoutBuf.toString(), stdoutBuf.length(), false, null, false),
                                new CapturedOutput(stderrBuf.toString(), stderrBuf.length(), false, null, false),
                                false,
                                "TIMED_OUT"
                        )
                );
            }

            outThread.join(1000);
            errThread.join(1000);

            int exitCode = process.exitValue();
            return new ProcessResult(
                    exitCode,
                    stdoutBuf.toString(),
                    stderrBuf.toString(),
                    Duration.ofNanos(elapsedNanos),
                    new ProcessOutput(
                            new CapturedOutput(stdoutBuf.toString(), stdoutBuf.length(), false, null, false),
                            new CapturedOutput(stderrBuf.toString(), stderrBuf.length(), false, null, false),
                            true,
                            null
                    )
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyContainer(containerName);
            if (process != null) process.destroyForcibly();
            return new ProcessResult(
                    -1,
                    "",
                    "",
                    Duration.ZERO,
                    new ProcessOutput(CapturedOutput.empty(), CapturedOutput.empty(), false, "INTERRUPTED")
            );
        } catch (IOException e) {
            destroyContainer(containerName);
            if (process != null) process.destroyForcibly();
            throw new IllegalStateException("Failed to launch sandboxed container: " + e.getMessage(), e);
        }
    }

    private List<String> buildContainerCommand(Command command, String containerName) {
        List<String> cmd = new ArrayList<>();
        cmd.add(engine);
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-i");
        cmd.add("--name");
        cmd.add(containerName);

        if (config.memoryLimit() != null && !config.memoryLimit().isBlank()) {
            cmd.add("--memory=" + config.memoryLimit());
        }
        if (config.cpuLimit() != null && !config.cpuLimit().isBlank()) {
            cmd.add("--cpus=" + config.cpuLimit());
        }
        if (!config.networkEnabled()) {
            cmd.add("--network");
            cmd.add("none");
        }

        cmd.add("-v");
        cmd.add(workspaceRoot.toAbsolutePath().toString() + ":/workspace:rw");
        if (spillRoot != null) {
            cmd.add("-v");
            cmd.add(spillRoot.toAbsolutePath().toString() + ":/outputs:rw");
        }
        cmd.add("-w");
        cmd.add("/workspace");

        for (Map.Entry<String, String> entry : command.environment().entrySet()) {
            cmd.add("-e");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }

        cmd.add(config.containerImage());
        cmd.addAll(command.argv());
        return cmd;
    }

    private void destroyContainer(String containerName) {
        try {
            new ProcessBuilder(engine, "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static void readStream(java.io.InputStream in, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                synchronized (out) {
                    if (out.length() + n <= 65536) {
                        out.append(buffer, 0, n);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
