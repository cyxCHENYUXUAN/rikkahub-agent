package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.*;

/** Foreground argv execution with bounded capture and actual observed-tree drain. */
public final class LocalProcessRunner implements ProcessRunner {
    private final LocalProcessSupervisor supervisor;
    LocalProcessRunner(LocalProcessSupervisor supervisor) { this.supervisor = supervisor; }

    @Override public ProcessResult run(Command command, CancellationToken cancellationToken) {
        var running = supervisor.start(command, cancellationToken, false);
        ProcessResult result = running.await();
        if (running.reason() != null) throw new LocalExecutionException(running.reason(), result);
        return result;
    }
}
