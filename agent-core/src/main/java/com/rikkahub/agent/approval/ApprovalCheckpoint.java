package com.rikkahub.agent.approval;

/** Implemented by the session writer. Both commits must succeed before side effects. */
public interface ApprovalCheckpoint {
    void asked(ApprovalRequest request);
    void decided(ApprovalRequest request, ApprovalOutcome outcome);
}
