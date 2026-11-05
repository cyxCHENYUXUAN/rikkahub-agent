package com.rikkahub.agent.approval;

/** A durable checkpoint failed; the writer must leave the turn open for recovery. */
public final class ApprovalPersistenceFailure extends RuntimeException {
    public ApprovalPersistenceFailure(Throwable cause){super("approval persistence failed",cause);}
}
