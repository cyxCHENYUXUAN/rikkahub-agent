package com.rikkahub.agent.session;

/** Stable identity of one persisted input. */
public record InputId(String value) {
    public InputId {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid input id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
