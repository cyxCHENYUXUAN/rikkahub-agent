package com.rikkahub.features.skills.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.rikkahub.agent.skill.Skill;
import com.rikkahub.agent.skill.SkillId;
import com.rikkahub.agent.skill.SkillSource;
import com.rikkahub.agent.skill.SkillSummary;
import com.rikkahub.platform.storage.StoragePaths;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Loads builtin skills from the classpath and user skills from data/skills.
 * Only SKILL.md is read; skill bodies never become system prompt instructions.
 */
@Service
public final class SkillCatalog implements SkillSource {
    private static final Set<String> FIELDS = Set.of("name", "description", "when-to-use",
            "allowed-tools", "version");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final StoragePaths paths;
    private volatile Map<String, Skill> skills = Map.of();

    public SkillCatalog(StoragePaths paths) {
        this.paths = paths;
        refresh();
    }

    public synchronized void refresh() {
        Map<String, Skill> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:skills/builtin/*/SKILL.md");
            for (Resource resource : resources) {
                String location = resource.getURL().getPath();
                int marker = location.lastIndexOf("/skills/builtin/");
                if (marker < 0) continue;
                String remainder = location.substring(marker + "/skills/builtin/".length());
                int slash = remainder.indexOf('/');
                if (slash <= 0) continue;
                String id = remainder.substring(0, slash);
                put(loaded, id, resource.getContentAsString(StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("builtin skills are unavailable", failure);
        }
        Path userRoot = paths.dataDirectory().resolve("skills");
        try {
            Files.createDirectories(userRoot);
            try (Stream<Path> entries = Files.list(userRoot)) {
                for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                    String id = directory.getFileName().toString();
                    if (!ID.matcher(id).matches()) continue;
                    Path file = directory.resolve("SKILL.md");
                    if (!Files.isRegularFile(file)) continue;
                    put(loaded, id, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("user skills are unavailable", failure);
        }
        skills = Map.copyOf(loaded);
    }

    private void put(Map<String, Skill> loaded, String id, String content) {
        SkillId skillId;
        try {
            skillId = new SkillId(id);
            loaded.put(id, parse(skillId, content));
        } catch (IllegalArgumentException invalid) {
            // Fail closed: a malformed skill is not advertised or loadable.
        }
    }

    static Skill parse(SkillId id, String content) {
        String[] lines = content.split("\\R", -1);
        if (lines.length < 3 || !lines[0].trim().equals("---")) {
            throw new IllegalArgumentException("skill requires frontmatter: " + id.value());
        }
        int closing = -1;
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.equals("---")) {
                closing = index;
                break;
            }
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("invalid skill frontmatter");
            String key = line.substring(0, colon).trim();
            if (!FIELDS.contains(key)) {
                throw new IllegalArgumentException("unsupported skill field: " + key);
            }
            fields.put(key, line.substring(colon + 1).trim());
        }
        if (closing < 0) throw new IllegalArgumentException("skill frontmatter is not closed");
        StringBuilder body = new StringBuilder();
        for (int index = closing + 1; index < lines.length; index++) {
            if (body.length() > 0) body.append('\n');
            body.append(lines[index]);
        }
        String description = fields.get("description");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("skill description is required: " + id.value());
        }
        String name = fields.getOrDefault("name", id.value());
        String whenToUse = fields.get("when-to-use");
        String version = fields.getOrDefault("version", "1");
        List<String> allowed = new ArrayList<>();
        String tools = fields.get("allowed-tools");
        if (tools != null && !tools.isBlank()) {
            for (String tool : tools.split(",")) {
                String value = tool.trim();
                if (!value.isEmpty()) allowed.add(value);
            }
        }
        return new Skill(new SkillSummary(id, name, description, whenToUse,
                List.copyOf(new LinkedHashSet<>(allowed)), version), body.toString().trim(), Map.of());
    }

    @Override public List<SkillSummary> summaries() {
        return skills.values().stream().map(Skill::summary)
                .sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList();
    }

    @Override public Optional<Skill> load(SkillId id) {
        return Optional.ofNullable(skills.get(id.value()));
    }
}
