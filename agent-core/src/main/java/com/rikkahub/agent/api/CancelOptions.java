package com.rikkahub.agent.api;

import java.util.Objects;

/**
 * Cancellation intent. {@code keepInbox} decides whether queued input survives
 * the cancellation and can be claimed by a later turn.
 */
public record CancelOptions(boolean keepInbox, CancelCause cause, String reason, Integer expectedTurn) {
    public CancelOptions {
        Objects.requireNonNull(cause, "cause");
        if (expectedTurn != null && expectedTurn < 1) {
            throw new IllegalArgumentException("expectedTurn must be positive");
        }
    }

    public CancelOptions(boolean keepInbox, CancelCause cause, String reason) {
        this(keepInbox, cause, reason, null);
    }

    /** Pins a delayed cancellation to one turn, never its successor. */
    public CancelOptions forTurn(int turn) {
        return new CancelOptions(keepInbox, cause, reason, turn);
    }

    public static CancelOptions user() {
        return new CancelOptions(true, CancelCause.USER, "user");
    }

    public static CancelOptions shutdown() {
        return new CancelOptions(false, CancelCause.SHUTDOWN, "shutdown");
    }
}
