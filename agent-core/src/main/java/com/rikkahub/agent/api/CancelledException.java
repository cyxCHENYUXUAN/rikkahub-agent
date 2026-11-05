package com.rikkahub.agent.api;

public class CancelledException extends RuntimeException {
    private final CancelCause cancelCause;

    public CancelledException(CancelCause cancelCause) {
        super("operation cancelled: " + cancelCause);
        this.cancelCause = cancelCause == null ? CancelCause.UNKNOWN : cancelCause;
    }

    public CancelCause cancelCause() {
        return cancelCause;
    }
}
