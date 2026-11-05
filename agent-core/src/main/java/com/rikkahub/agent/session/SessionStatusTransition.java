package com.rikkahub.agent.session;

import java.util.Objects;

/** Compare-and-set of session status, applied with the event batch. */
public record SessionStatusTransition(SessionStatus expected, SessionStatus next) {
    public SessionStatusTransition {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
    }
}
