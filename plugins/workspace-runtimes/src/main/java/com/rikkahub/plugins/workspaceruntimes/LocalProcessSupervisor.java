package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Windows Job Object ownership; root exit never abandons a descendant. */
final class LocalProcessSupervisor implements AutoCloseable {
    private final LocalPaths paths;
    private final RuntimeLimits limits;
    private final LocalOutputStore store;
    @FunctionalInterface
    interface ProcessFactory {
        WindowsOwnership start(List<String> argv, Path cwd, Map<String, String> environment);
    }
    private final ProcessFactory processes;
    private final ExecutorService workers;
    private final Set<Running> active = new HashSet<>();
    private boolean closed;

    LocalProcessSupervisor(LocalPaths paths, RuntimeLimits limits, LocalOutputStore store) {
        this(paths, limits, store, WindowsOwnership::start);
    }
    LocalProcessSupervisor(LocalPaths paths, RuntimeLimits limits, LocalOutputStore store, ProcessFactory processes) {
        this.paths = paths; this.limits = limits; this.store = store;
        this.processes = Objects.requireNonNull(processes);
        workers = Executors.newFixedThreadPool(limits.maxProcesses() * 3, r -> {
            Thread thread = new Thread(r, "workspace-process");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized Running start(Command command, CancellationToken token, boolean interactive) {
        Objects.requireNonNull(token);
        if (closed) throw new IllegalStateException("execution environment closed");
        token.throwIfCancelled();
        if (active.size() >= limits.maxProcesses()) throw new IllegalStateException("process limit reached");
        if (command.timeout().isZero() || command.timeout().isNegative()) throw new IllegalArgumentException("timeout must be positive");
        Path cwd = paths.resolve(command.cwd());
        List<String> argv = new ArrayList<>(command.argv());
        for (String arg : argv) if (arg.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid argv");
        argv.set(0, executable(argv.get(0)));
        if (windows() && (argv.get(0).toLowerCase(Locale.ROOT).endsWith(".cmd") || argv.get(0).toLowerCase(Locale.ROOT).endsWith(".bat")))
            throw new IllegalArgumentException("batch execution requires an explicit command interpreter");
        Map<String, String> environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        environment.put("PATH", safePath());
        if (windows()) {
            String systemRoot = systemRoot();
            environment.put("SystemRoot", systemRoot);
            environment.put("WINDIR", systemRoot);
        }
        for (var entry : command.environment().entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().indexOf('=') >= 0 || entry.getKey().indexOf('\0') >= 0
                    || entry.getValue().indexOf('\0') >= 0) throw new IllegalArgumentException("invalid environment entry");
            environment.put(entry.getKey(), entry.getValue());
        }
        token.throwIfCancelled();
        // CreateProcess accepts a pathname, not a relative directory handle. The bound,
        // non-deletable guard keeps the last directory nonempty (NTFS rejects reparse
        // insertion on nonempty directories); each ancestor contains its pinned child.
        // Accordingly process startup requires a writable cwd, and fails closed otherwise.
        try (var lease = paths.lease(cwd); var guard = lease.directory().temporary()) {
            WindowsOwnership process = processes.start(argv, cwd, environment);
            Running running = new Running(process, command.timeout(), token);
            active.add(running);
            running.launch(interactive);
            return running;
        }
    }

    private String executable(String executable) {
        if (executable.isBlank()) throw new IllegalArgumentException("empty executable");
        Path value = Path.of(executable);
        if (windows()) {
            String root = value.getRoot() == null ? "" : value.getRoot().toString();
            if (root.startsWith("\\\\?\\") || root.startsWith("\\\\.\\")) throw LocalPaths.denied();
            for (Path name : value) LocalPaths.validateName(name.toString());
        }
        if (value.isAbsolute()) {
            if (windows() && !Files.exists(value) && Files.isRegularFile(Path.of(value + ".exe"))) value=Path.of(value + ".exe");
            return value.normalize().toString();
        } // arbitrary execution is separately approved
        if (value.getNameCount() > 1 || executable.contains("/") || executable.contains("\\"))
            return paths.resolve(value).toString();
        // Never resolve an unqualified command through the parent's potentially secret/untrusted PATH.
        for (String directory : safePath().split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            for (String suffix : windows() ? List.of("", ".exe", ".com") : List.of("")) {
                Path candidate = Path.of(directory).resolve(executable + suffix);
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        throw new IllegalArgumentException("executable not found in trusted system path; supply an absolute path");
    }
    private static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static String systemRoot() { return System.getenv().getOrDefault("SystemRoot", "C:\\Windows"); }
    private static String safePath() {
        return windows() ? Path.of(systemRoot(), "System32") + ";" + systemRoot() : "/usr/bin:/bin";
    }

    @Override public void close() {
        List<Running> snapshot;
        synchronized (this) {
            closed = true;
            snapshot = List.copyOf(active);
            snapshot.forEach(r -> r.stop("CLOSED"));
        }
        snapshot.forEach(Running::await);
        workers.shutdown();
        boolean interrupted = false;
        for (;;) {
            try { if (workers.awaitTermination(1, TimeUnit.DAYS)) break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    final class Running {
        private final WindowsOwnership process;
        private final Duration timeout;
        private final long started = System.nanoTime();
        private final LocalOutputCapture stdout = new LocalOutputCapture(limits, store);
        private final LocalOutputCapture stderr = new LocalOutputCapture(limits, store);
        private final AtomicReference<String> reason = new AtomicReference<>();
        private final CompletableFuture<ProcessResult> completion = new CompletableFuture<>();
        private final AutoCloseable cancellation;
        private Future<?> outPump;
        private Future<?> errPump;
        private volatile ProcessResult result;

        Running(WindowsOwnership process, Duration timeout, CancellationToken token) {
            this.process = process; this.timeout = timeout;
            cancellation = token.onCancel(() -> stop("CANCELLED"));
        }
        void launch(boolean interactive) {
            outPump = workers.submit(() -> stdout.pump(process.stdout));
            errPump = workers.submit(() -> stderr.pump(process.stderr));
            workers.execute(this::monitor);
            if (!interactive) closeInput();
        }
        void stop(String why) { if (!completion.isDone()) reason.compareAndSet(null, why); }
        boolean done() { return completion.isDone(); }
        String reason() { return reason.get(); }
        ProcessOutput output() {
            ProcessResult finished = result;
            if (finished != null) return finished.output();
            return new ProcessOutput(stdout.snapshot(), stderr.snapshot(), false,
                    stdout.failed() || stderr.failed() ? "OUTPUT_FAILED" : null);
        }
        OptionalInt exitCode() {
            ProcessResult finished = result;
            return finished == null ? OptionalInt.empty() : OptionalInt.of(finished.exitCode());
        }
        void write(String text) {
            Objects.requireNonNull(text);
            if (text.length() > limits.maxFileBytes()) throw new IllegalArgumentException("terminal input exceeds limit");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > limits.maxFileBytes()) throw new IllegalArgumentException("terminal input exceeds limit");
            try { process.stdin.write(bytes); process.stdin.flush(); }
            catch (IOException e) { throw LocalPaths.io(e); }
        }
        void closeInput() {
            try { process.stdin.close(); }
            catch (IOException e) { stop("INPUT_FAILED"); }
        }
        ProcessResult await() {
            boolean interrupted = false;
            try {
                for (;;) {
                    try { return completion.get(); }
                    catch (InterruptedException e) { interrupted = true; stop("INTERRUPTED"); }
                    catch (ExecutionException e) { throw new IllegalStateException("process supervision failed", e.getCause()); }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        private void monitor() {
            long timeoutNanos;
            try { timeoutNanos = timeout.toNanos(); } catch (ArithmeticException e) { timeoutNanos = Long.MAX_VALUE; }
            boolean interrupted = false;
            boolean treeDrained = false;
            boolean supervisionFailed = false;
            int exitCode = -1;
            try {
                for (;;) {
                    if (System.nanoTime() - started >= timeoutNanos) stop("TIMED_OUT");
                    if (stdout.failed() || stderr.failed()) stop("OUTPUT_FAILED");
                    if (reason.get() != null || !process.rootAlive()) process.terminate();
                    if (!process.treeAlive() && outPump.isDone() && errPump.isDone()) { treeDrained = true; break; }
                    try { Thread.sleep(10); } catch (InterruptedException e) { interrupted = true; stop("INTERRUPTED"); }
                }
            } catch (Throwable failure) {
                supervisionFailed = true;
                reason.compareAndSet(null, "SUPERVISION_FAILED");
                // The failed monitor must not abandon native ownership or publish a fictitious drain.
                try { treeDrained = process.abortAndDrain(); }
                catch (Throwable recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            } finally {
                try { closeInput(); } catch (Throwable failure) { supervisionFailed = true; }
                // A completed/cancelled Future is not proof of a pump exiting. These futures are
                // never cancelled; get waits for the real callable, including stream close, to finish.
                for (Future<?> pump : new Future<?>[]{outPump, errPump}) {
                    if (pump == null) continue;
                    for (;;) {
                        try { pump.get(); break; }
                        catch (InterruptedException e) { interrupted = true; stop("INTERRUPTED"); }
                        catch (ExecutionException | CancellationException e) { supervisionFailed = true; treeDrained = false; break; }
                    }
                }
                try { exitCode = process.exitCode(); } catch (Throwable failure) { supervisionFailed = true; }
                try { process.close(); } catch (Throwable failure) { supervisionFailed = true; }
                try { cancellation.close(); } catch (Exception failure) { supervisionFailed = true; }
                String failure = supervisionFailed ? "SUPERVISION_FAILED" : stdout.failed() || stderr.failed() ? "OUTPUT_FAILED" : null;
                if (failure != null) reason.compareAndSet(null, failure);
                ProcessOutput output = new ProcessOutput(stdout.snapshot(), stderr.snapshot(),
                        treeDrained && stdout.drained() && stderr.drained(), failure);
                ProcessResult finished = new ProcessResult(exitCode, output.stdout().text(), output.stderr().text(),
                        Duration.ofNanos(System.nanoTime() - started), output);
                synchronized (LocalProcessSupervisor.this) { active.remove(this); }
                result = finished;
                completion.complete(finished);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
