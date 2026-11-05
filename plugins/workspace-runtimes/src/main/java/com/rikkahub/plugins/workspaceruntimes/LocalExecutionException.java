package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.execution.ProcessExecutionException;
import com.rikkahub.agent.execution.ProcessResult;

/** Compatible runtime-specific name; core-only consumers catch ProcessExecutionException. */
public final class LocalExecutionException extends ProcessExecutionException {
    LocalExecutionException(String reason, ProcessResult result) {
        super(reason, result);
    }
}
