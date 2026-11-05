package com.rikkahub.agent.execution;

import com.rikkahub.agent.api.CancellationToken;

public interface Terminal {
    TerminalHandle open(TerminalSpec spec, CancellationToken signal);
}
