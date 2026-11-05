package com.rikkahub.agent.api;

import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.message.Message;

/**
 * Mutable working copy handed to pre-step hooks. Inputs are read-only; hooks
 * may rewrite the message list or reject the whole step.
 */
public final class PreStepContext {
    private final List<InboxItem> inputs;
    private List<Message> messages;
    private String rejectionReason;
    private long revision;

    public PreStepContext(List<InboxItem> inputs, List<Message> messages) {
        this.inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public List<InboxItem> inputs() {
        return inputs;
    }

    public List<Message> messages() {
        return messages;
    }

    public void rewrite(List<Message> messages) {
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        revision++;
    }

    /** Monotonic working-copy revision, used to recognize a terminal rewrite. */
    public long revision() {
        return revision;
    }

    public void reject(String reason) {
        this.rejectionReason = Objects.requireNonNull(reason, "reason");
    }

    /** Returns null when the step has not been rejected. */
    public String rejectionReason() {
        return rejectionReason;
    }
}
