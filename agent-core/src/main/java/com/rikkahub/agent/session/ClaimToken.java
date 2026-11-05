package com.rikkahub.agent.session;

/** Opaque lease capability issued by InboxStore.claim. */
public record ClaimToken(String value) {
    public ClaimToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("claim token must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
