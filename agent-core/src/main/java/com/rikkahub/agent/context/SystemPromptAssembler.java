package com.rikkahub.agent.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic, data-only system prompt assembly. No templating or evaluation. */
public final class SystemPromptAssembler {
    private SystemPromptAssembler() {
    }

    /** Sorts by order then name and rejects duplicate names. */
    public static List<PromptSection> ordered(List<PromptSection> sections) {
        Objects.requireNonNull(sections, "sections");
        Set<String> names = new HashSet<>();
        for (PromptSection section : sections) {
            Objects.requireNonNull(section, "section");
            if (!names.add(section.name())) {
                throw new IllegalArgumentException("duplicate prompt section: " + section.name());
            }
        }
        List<PromptSection> copy = new ArrayList<>(sections);
        copy.sort(Comparator.comparingInt(PromptSection::order).thenComparing(PromptSection::name));
        return List.copyOf(copy);
    }

    /** Joins non-blank section contents with a blank line. */
    public static String assemble(List<PromptSection> sections) {
        StringBuilder out = new StringBuilder();
        for (PromptSection section : ordered(sections)) {
            if (section.content().isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(section.content());
        }
        return out.toString();
    }
}
