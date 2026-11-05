package com.rikkahub.agent.skill;

import java.util.List;
import java.util.Objects;

/** Progressive-disclosure view: only name/description/metadata enter the system prompt. */
public record SkillSummary(SkillId id, String name, String description, String whenToUse,
                           List<String> allowedTools, String version) {
    public SkillSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(version, "version");
        if (name.isBlank() || description.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("skill summary fields must not be blank");
        }
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
    }
}
