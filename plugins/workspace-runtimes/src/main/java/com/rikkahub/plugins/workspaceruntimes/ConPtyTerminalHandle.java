package com.rikkahub.plugins.workspaceruntimes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.CapturedOutput;
import com.rikkahub.agent.execution.ProcessOutput;
import com.rikkahub.agent.execution.TerminalHandle;
import com.rikkahub.plugins.workspaceruntimes.conpty.ConPtyProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-fidelity interactive terminal handle powered by Windows ConPTY pseudo console.
 * Fully supports resize, bidirectional ANSI streaming, and graceful shutdown.
 */
public final class ConPtyTerminalHandle implements TerminalHandle {
    private static final Logger LOG = LoggerFactory.getLogger(ConPtyTerminalHandle.class);

    private final ConPtyProcess process;
    private final LocalOutputCapture capture;
    private final Thread readerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConPtyTerminalHandle(ConPtyProcess process, LocalOutputStore store, RuntimeLimits limits, CancellationToken token) {
        this.process = process;
        this.capture = new LocalOutputCapture(limits, store);

        this.readerThread = new Thread(() -> capture.pump(process.stdout()), "conpty-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        if (token != null) {
            token.onCancel(() -> {
                LOG.info("ConPTY terminal session cancelled by token");
                close();
            });
        }
    }

    @Override
    public String mode() {
        return "CONPTY";
    }

    @Override
    public void write(String data) {
        if (closed.get() || data == null) return;
        try {
            OutputStream out = process.stdin();
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            LOG.debug("ConPTY write failed: {}", e.getMessage());
        }
    }

    @Override
    public void resize(int columns, int rows) {
        if (closed.get()) return;
        process.resize(columns, rows);
    }

    @Override
    public void closeInput() {
        if (closed.get()) return;
        try {
            process.stdin().close();
        } catch (IOException ignored) {}
    }

    @Override
    public ProcessOutput output() {
        return new ProcessOutput(capture.snapshot(), CapturedOutput.empty(), capture.drained(), null);
    }

    @Override
    public OptionalInt exitCode() {
        return process.exitCode();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            process.close();
            try {
                readerThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
