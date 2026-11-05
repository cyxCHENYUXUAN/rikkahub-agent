package com.rikkahub.agent.config;

import java.time.Duration;
import java.util.Objects;

public record Limits(Duration turnTimeout, int maxSteps, Duration stepTimeout,
                     int maxToolOutputBytes) {
    public Limits {
        turnTimeout = requirePositive(turnTimeout, "turnTimeout");
        stepTimeout = requirePositive(stepTimeout, "stepTimeout");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (maxToolOutputBytes <= 0) {
            throw new IllegalArgumentException("maxToolOutputBytes must be positive");
        }
    }

    public static Limits defaults() {
        return new Limits(Duration.ofMinutes(10), 50, Duration.ofMinutes(5), 262_144);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
