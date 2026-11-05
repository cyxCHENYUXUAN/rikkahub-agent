package com.rikkahub.agent.session;

public record SessionId(String value) {
    public SessionId {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid session id: " + value);
        }
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
