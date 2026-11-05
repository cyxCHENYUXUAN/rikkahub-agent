package com.rikkahub.agent.execution;

import com.rikkahub.agent.api.CancellationToken;

public interface ProcessRunner {
    ProcessResult run(Command command, CancellationToken signal);
}
