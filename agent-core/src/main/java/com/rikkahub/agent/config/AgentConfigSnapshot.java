package com.rikkahub.agent.config;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.rikkahub.agent.approval.ApprovalPolicy;
import com.rikkahub.agent.model.GenerationOptions;
import com.rikkahub.agent.model.ModelRef;
import com.rikkahub.agent.port.Clock;

/**
 * A frozen view of everything that can change model behavior. The fingerprint
 * identifies this exact configuration across processes.
 */
public record AgentConfigSnapshot(long version, String hash, Instant frozenAt,
                                  ModelRef model, GenerationOptions generation,
                                  ContextPolicy context, ApprovalPolicy approval,
                                  ToolPolicy tools, List<String> skills,
                                  String promptProfile, Duration turnTimeout, int maxSteps) {
    /**
     * Canonical serialization only: map keys are sorted recursively and no
     * identity-based values are allowed, so equal configs yield equal bytes.
     */
    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public AgentConfigSnapshot {
        Objects.requireNonNull(frozenAt, "frozenAt");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(tools, "tools");
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
        for (String skill : skills) {
            Objects.requireNonNull(skill, "skills must not contain null");
        }
        Objects.requireNonNull(promptProfile, "promptProfile");
        Objects.requireNonNull(turnTimeout, "turnTimeout");
        if (turnTimeout.isZero() || turnTimeout.isNegative()) {
            throw new IllegalArgumentException("turnTimeout must be positive");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        String computed = computeHash(version, model, generation, context, approval, tools, skills,
                promptProfile, turnTimeout, maxSteps);
        if (hash == null) {
            hash = computed;
        } else if (!hash.equals(computed)) {
            throw new IllegalArgumentException("hash does not match the frozen configuration");
        }
    }

    /** Compatibility constructor for callers without a recorded freeze time. */
    public AgentConfigSnapshot(ModelRef model, GenerationOptions generation, ContextPolicy context,
                               ApprovalPolicy approval, ToolPolicy tools, List<String> skills,
                               String promptProfile, Duration turnTimeout, int maxSteps,
                               long version) {
        this(version, null, Instant.EPOCH, model, generation, context, approval, tools, skills,
                promptProfile, turnTimeout, maxSteps);
    }

    /** Freezes behavior and records time from the host's injected clock. */
    public static AgentConfigSnapshot freeze(long version, ModelRef model,
                                             GenerationOptions generation, ContextPolicy context,
                                             ApprovalPolicy approval, ToolPolicy tools,
                                             List<String> skills, String promptProfile,
                                             Duration turnTimeout, int maxSteps, Clock clock) {
        return new AgentConfigSnapshot(version, null, Objects.requireNonNull(clock, "clock").now(),
                model, generation, context, approval, tools, skills, promptProfile, turnTimeout,
                maxSteps);
    }

    /** Compatibility alias for the stable SHA-256 hash. */
    public String fingerprint() {
        return hash;
    }

    /** Hashes the version and all behavior fields; freeze time is not behavior. */
    private static String computeHash(long version, ModelRef model, GenerationOptions generation,
                                      ContextPolicy context, ApprovalPolicy approval,
                                      ToolPolicy tools, List<String> skills, String promptProfile,
                                      Duration turnTimeout, int maxSteps) {
        Map<String, Object> canonical = new LinkedHashMap<>();

        Map<String, Object> modelFields = new LinkedHashMap<>();
        modelFields.put("provider", model.provider());
        modelFields.put("model", model.model());
        canonical.put("model", modelFields);

        Map<String, Object> generationFields = new LinkedHashMap<>();
        generationFields.put("temperature", generation.temperature());
        generationFields.put("topP", generation.topP());
        generationFields.put("maxOutputTokens", generation.maxOutputTokens());
        generationFields.put("stopSequences", generation.stopSequences());
        generationFields.put("extra", generation.extra());
        canonical.put("generation", generationFields);

        Map<String, Object> contextFields = new LinkedHashMap<>();
        contextFields.put("contextWindowTokens", context.contextWindowTokens());
        contextFields.put("compactionThresholdTokens", context.compactionThresholdTokens());
        contextFields.put("keepRecentTokens", context.keepRecentTokens());
        contextFields.put("systemPromptUpdate", context.systemPromptUpdate().name());
        canonical.put("context", contextFields);

        canonical.put("approval", approval.name());

        Map<String, Object> toolFields = new LinkedHashMap<>();
        toolFields.put("allowedTools", List.copyOf(new TreeSet<>(tools.allowedTools())));
        toolFields.put("allowAll", tools.allowAll());
        toolFields.put("defaultApproval", tools.defaultApproval().name());
        // Omit extension defaults so pre-P3 snapshots retain their recorded hashes.
        if (tools.preset() != PermissionPreset.WORKSPACE_WRITE || !tools.rules().isEmpty()) {
            toolFields.put("preset", tools.preset().name());
            toolFields.put("rules", tools.rules());
        }
        canonical.put("tools", toolFields);

        canonical.put("skills", skills);
        canonical.put("promptProfile", promptProfile);
        canonical.put("turnTimeout", turnTimeout.toString());
        canonical.put("maxSteps", maxSteps);
        canonical.put("version", version);

        try {
            byte[] bytes = CANONICAL_MAPPER.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to canonicalize agent config", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
