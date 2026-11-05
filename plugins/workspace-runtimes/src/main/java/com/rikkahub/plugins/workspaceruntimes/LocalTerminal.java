package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.*;
import com.rikkahub.plugins.workspaceruntimes.conpty.ConPtyProcess;
import com.rikkahub.plugins.workspaceruntimes.conpty.ConPtySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * Adaptive interactive terminal service.
 * Automatically prefers native Windows ConPTY emulation with full ANSI and resize support;
 * gracefully falls back to reliable PIPE mode when ConPTY is unsupported or disabled.
 */
public final class LocalTerminal implements Terminal, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerminal.class);

    private final LocalProcessSupervisor supervisor;
    private final RuntimeLimits limits;
    private final LocalOutputStore store;
    private final int limit;
    private final Set<TerminalHandle> activeHandles = new HashSet<>();
    private boolean closed;

    LocalTerminal(LocalProcessSupervisor supervisor, RuntimeLimits limits) {
        this(supervisor, limits, null);
    }

    LocalTerminal(LocalProcessSupervisor supervisor, RuntimeLimits limits, LocalOutputStore store) {
        this.supervisor = supervisor;
        this.limits = limits;
        this.store = store;
        this.limit = limits.maxTerminals();
    }

    @Override
    public synchronized TerminalHandle open(TerminalSpec spec, CancellationToken token) {
        if (closed) throw new IllegalStateException("terminal service closed");
        activeHandles.removeIf(h -> {
            try {
                return h.exitCode().isPresent();
            } catch (Exception e) {
                return false;
            }
        });
        if (activeHandles.size() >= limit) throw new IllegalStateException("terminal limit reached");

        // Attempt ConPTY if platform supports it and store is available
        if (ConPtySupport.isSupported() && store != null) {
            try {
                ConPtyProcess process = ConPtyProcess.start(spec.argv(), spec.cwd(), spec.environment(), 80, 24);
                ConPtyTerminalHandle conPtyHandle = new ConPtyTerminalHandle(process, store, limits, token);
                activeHandles.add(conPtyHandle);
                LOG.info("ConPTY terminal session initialized successfully for cwd={}", spec.cwd());
                return conPtyHandle;
            } catch (Throwable t) {
                LOG.warn("ConPTY initialization failed, falling back to PIPE terminal: {}", t.getMessage());
            }
        }

        // Graceful fallback to PIPE mode
        var running = supervisor.start(new Command(spec.argv(), spec.cwd(), spec.environment(), Duration.ofSeconds(Long.MAX_VALUE)), token, true);
        var pipeHandle = new PipeHandle(running);
        activeHandles.add(pipeHandle);
        return pipeHandle;
    }

    @Override
    public void close() {
        List<TerminalHandle> snapshot;
        synchronized (this) {
            closed = true;
            snapshot = List.copyOf(activeHandles);
            activeHandles.clear();
        }
        for (TerminalHandle handle : snapshot) {
            try {
                handle.close();
            } catch (Exception ignored) {}
        }
    }

    private final class PipeHandle implements TerminalHandle {
        final LocalProcessSupervisor.Running running;

        PipeHandle(LocalProcessSupervisor.Running running) {
            this.running = running;
        }

        @Override
        public String mode() {
            return "PIPE";
        }

        @Override
        public void write(String data) {
            running.write(data);
        }

        @Override
        public void closeInput() {
            running.closeInput();
        }

        @Override
        public void resize(int columns, int rows) {
            throw new UnsupportedOperationException("PIPE terminal resize unsupported");
        }

        @Override
        public ProcessOutput output() {
            return running.output();
        }

        @Override
        public OptionalInt exitCode() {
            return running.exitCode();
        }

        @Override
        public void close() {
            running.stop("CLOSED");
            running.await();
            synchronized (LocalTerminal.this) {
                activeHandles.remove(this);
            }
        }
    }
}
