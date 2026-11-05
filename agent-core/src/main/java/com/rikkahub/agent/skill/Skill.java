package com.rikkahub.agent.skill;

import java.util.Map;
import java.util.Objects;

/** Full skill body loaded only on demand; body is untrusted data, not instructions to the host. */
public record Skill(SkillSummary summary, String body, Map<String, String> resources) {
    public Skill {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(body, "body");
        resources = Map.copyOf(Objects.requireNonNull(resources, "resources"));
        if (body.isBlank()) {
            throw new IllegalArgumentException("skill body must not be blank");
        }
    }
}
