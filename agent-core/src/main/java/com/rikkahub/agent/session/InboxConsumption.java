package com.rikkahub.agent.session;

import java.util.Objects;

/** An active claim to consume atomically with the session commit. */
public record InboxConsumption(ClaimToken claimToken, InputId inputId) {
    public InboxConsumption {
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(inputId, "inputId");
    }
}
