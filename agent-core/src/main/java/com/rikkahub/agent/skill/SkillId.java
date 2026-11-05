package com.rikkahub.agent.skill;

import java.util.Objects;

public record SkillId(String value) {
    public SkillId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid skill id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
