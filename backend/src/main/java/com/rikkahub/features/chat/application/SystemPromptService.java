package com.rikkahub.features.chat.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rikkahub.agent.context.PromptSection;
import com.rikkahub.agent.context.SystemPromptAssembler;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.features.assistants.application.AssistantApplicationService.AssistantProfile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Loads read-only prompt templates and renders only whitelisted placeholders.
 * Prompt files are data; no expression language or script evaluation exists.
 */
@Service
public final class SystemPromptService {
    private static final List<String> TEMPLATES = List.of(
            "00-identity", "10-environment", "20-tools", "30-skills", "40-memory", "90-guardrails");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private final Clock clock;
    private final com.rikkahub.features.skills.application.SkillCatalog skills;
    private final Map<String, String> templates;

    public SystemPromptService(Clock clock, com.rikkahub.features.skills.application.SkillCatalog skills) {
        this.clock = clock;
        this.skills = skills;
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String name : TEMPLATES) {
            try {
                loaded.put(name, new ClassPathResource("prompts/system/" + name + ".md")
                        .getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("prompt template is unavailable: " + name, failure);
            }
        }
        this.templates = Map.copyOf(loaded);
    }

    /** Renders identity/environment/tools/assistant/guardrails in deterministic order. */
    public String render(AssistantProfile profile, Path cwd) {
        Instant now = clock.now();
        String time = DateTimeFormatter.ISO_INSTANT.format(now);
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now);
        String skillList = skills.summaries().isEmpty() ? "No skills are installed."
                : skills.summaries().stream().map(skill -> "- " + skill.id().value() + ": "
                        + skill.description() + (skill.whenToUse() == null ? ""
                                : " (" + skill.whenToUse() + ")"))
                        .collect(java.util.stream.Collectors.joining("\n"));
        List<PromptSection> sections = new ArrayList<>();
        sections.add(new PromptSection("identity", 0, render("00-identity", cwd, date, time, "")));
        sections.add(new PromptSection("environment", 10, render("10-environment", cwd, date, time, "")));
        sections.add(new PromptSection("tools", 20, render("20-tools", cwd, date, time, "")));
        sections.add(new PromptSection("skills", 30, render("30-skills", cwd, date, time, skillList)));
        sections.add(new PromptSection("memory", 40, render("40-memory", cwd, date, time, "")));
        if (profile != null && profile.systemPrompt() != null && !profile.systemPrompt().isBlank()) {
            sections.add(new PromptSection("assistant", 50, profile.systemPrompt()));
        }
        sections.add(new PromptSection("guardrails", 90, render("90-guardrails", cwd, date, time, "")));
        return SystemPromptAssembler.assemble(sections);
    }

    private String render(String name, Path cwd, String date, String time, String skillsText) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalStateException("unknown prompt template: " + name);
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "cwd" -> cwd == null ? "" : cwd.toString();
                case "date" -> date;
                case "time" -> time;
                case "skills" -> skillsText;
                default -> throw new IllegalArgumentException(
                        "unsupported prompt placeholder: " + matcher.group(1));
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
