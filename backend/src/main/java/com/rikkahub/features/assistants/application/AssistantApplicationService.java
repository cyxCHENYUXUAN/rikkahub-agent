package com.rikkahub.features.assistants.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;
import com.rikkahub.agent.config.ToolRule;

import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.model.GenerationOptions;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.features.providers.application.ProviderApplicationService;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.TransactionRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Validated, non-credential assistant configuration; creation joins the idempotency transaction. */
@Service
public final class AssistantApplicationService {
    private static final Set<String> FIELDS = Set.of("id", "name", "providerId", "model", "systemPrompt",
            "contextWindowTokens", "maxOutputTokens", "temperature",
            "toolPermissionMode", "approvalPolicy", "toolRules");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final Clock clock;
    private final IdGenerator ids;
    private final ProviderApplicationService providers;

    public AssistantApplicationService(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec,
                                        Clock clock, IdGenerator ids, ProviderApplicationService providers) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = codec;
        this.clock = clock;
        this.ids = ids;
        this.providers = providers;
    }

    public AssistantProfile createInTransaction(JsonNode input) {
        requireTransaction();
        if (input == null || !input.isObject()
                || input.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))) {
            throw invalid("Assistant input contains unsupported fields or has an invalid shape.");
        }
        String id = optional(input, "id", 128);
        if (id == null) {
            id = ids.newId("assistant");
        }
        identifier(id);
        String name = required(input, "name", 128);
        String providerId = required(input, "providerId", 128);
        identifier(providerId);
        String model = required(input, "model", 256);
        String systemPrompt = prompt(input.get("systemPrompt"));
        int contextWindow = integer(input, "contextWindowTokens", 8192, 2_000_000);
        int maxOutput = integer(input, "maxOutputTokens", 4096, Math.min(contextWindow, 1_000_000));
        double temperature = temperature(input.get("temperature"));
        var provider = providers.requireInTransaction(providerId);
        if (!provider.enabled()) {
            throw new ApiFailure(503, "PROVIDER_DISABLED", "The selected provider is disabled.");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM assistants WHERE id=:id").param("id", id)
                .query(Integer.class).single() != 0) {
            throw conflict();
        }
        GenerationOptions generation = new GenerationOptions(temperature, 1.0, maxOutput, List.of(), Map.of());
        Permissions permissions = permissions(input);
        String configuration = codec.encode(new StoredConfiguration(systemPrompt, contextWindow, generation,
                permissions.mode(), permissions.approval(), permissions.rules()));
        String at = EventCodec.timestamp(clock.now());
        try {
            jdbc.sql("""
                    INSERT INTO assistants(id,name,provider_id,model,config_json,version,created_at,updated_at)
                    VALUES(:id,:name,:provider,:model,:config,0,:at,:at)
                    """).param("id", id).param("name", name).param("provider", providerId).param("model", model)
                    .param("config", configuration).param("at", at).update();
        } catch (DuplicateKeyException duplicate) {
            throw conflict();
        }
        return new AssistantProfile(id, name, providerId, model, systemPrompt, contextWindow, generation,
                permissions.mode(), permissions.approval(), permissions.rules(), 0);
    }

    public AssistantProfile requireInTransaction(String id) {
        requireTransaction();
        identifier(id);
        return jdbc.sql("SELECT * FROM assistants WHERE id=:id").param("id", id)
                .query((row, number) -> profile(row)).optional()
                .orElseThrow(() -> new ApiFailure(404, "ASSISTANT_NOT_FOUND", "The assistant was not found."));
    }

    public AssistantProfile get(String id) {
        return transactions.read(() -> requireInTransaction(id));
    }

    public List<AssistantProfile> list() {
        return transactions.read(() -> jdbc.sql("SELECT * FROM assistants ORDER BY created_at,id")
                .query((row, number) -> profile(row)).list());
    }

    private AssistantProfile profile(ResultSet row) throws SQLException {
        StoredConfiguration config = codec.decode(row.getString("config_json"), StoredConfiguration.class);
        // Stored JSON predating P3 has no permission fields; fail closed to the safe defaults.
        Permissions permissions = new Permissions(
                config.toolPermissionMode() == null ? "WORKSPACE_WRITE" : config.toolPermissionMode(),
                config.approvalPolicy() == null ? "ASK" : config.approvalPolicy(),
                config.toolRules() == null ? List.of() : config.toolRules());
        return new AssistantProfile(row.getString("id"), row.getString("name"), row.getString("provider_id"),
                row.getString("model"), config.systemPrompt(), config.contextWindowTokens(), config.generation(),
                permissions.mode(), permissions.approval(), permissions.rules(), row.getLong("version"));
    }

    private static String required(JsonNode input, String field, int limit) {
        String value = optional(input, field, limit);
        if (value == null) {
            throw invalid("A required assistant field is missing.");
        }
        return value;
    }

    private static String optional(JsonNode input, String field, int limit) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalid("Assistant text fields must contain strings.");
        }
        String text = value.stringValue().trim();
        if (text.isEmpty() || text.length() > limit) {
            throw invalid("An assistant text field has an invalid length.");
        }
        return text;
    }

    private static String prompt(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isString() || value.stringValue().length() > 262_144) {
            throw invalid("systemPrompt must be a string within the supported size limit.");
        }
        return value.stringValue();
    }

    private static int integer(JsonNode input, String field, int fallback, int maximum) {
        JsonNode value = input.get(field);
        int result = fallback;
        if (value != null) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid("Assistant token budgets must be whole numbers.");
            }
            result = value.intValue();
        }
        if (result <= 0 || result > maximum) {
            throw invalid("Assistant token budgets are outside the supported range.");
        }
        return result;
    }

    private static double temperature(JsonNode value) {
        if (value == null) {
            return 0.7;
        }
        if (!value.isNumber()) {
            throw invalid("temperature must be a number within [0, 2].");
        }
        double result = value.doubleValue();
        if (!Double.isFinite(result) || result < 0.0 || result > 2.0) {
            throw invalid("temperature must be a number within [0, 2].");
        }
        return result;
    }

    private static void identifier(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw invalid("The configuration ID has an invalid format.");
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("assistant operation requires its caller's transaction");
        }
    }

    private static ApiFailure invalid(String detail) {
        return new ApiFailure(400, "INVALID_ASSISTANT", detail);
    }

    private static ApiFailure conflict() {
        return new ApiFailure(409, "ASSISTANT_CONFLICT", "An assistant with this ID already exists.");
    }

    public record AssistantProfile(String id, String name, String providerId, String model, String systemPrompt,
                                   int contextWindowTokens, GenerationOptions generation, String toolPermissionMode,
                                   String approvalPolicy, List<ToolRule> toolRules, long version) {
    }

    private record StoredConfiguration(String systemPrompt, int contextWindowTokens, GenerationOptions generation,
                                       String toolPermissionMode, String approvalPolicy, List<ToolRule> toolRules) {
    }

    /** Validated P3 permission block; unknown fields and values fail closed. */
    private record Permissions(String mode, String approval, List<ToolRule> rules) {}

    private Permissions permissions(JsonNode input) {
        JsonNode mode = input.get("toolPermissionMode");
        String permissionMode = mode == null ? "WORKSPACE_WRITE" : textEnum(mode, "toolPermissionMode",
                Set.of("READ_ONLY", "WORKSPACE_WRITE", "FULL_ACCESS"));
        JsonNode approval = input.get("approvalPolicy");
        String approvalPolicy = approval == null ? "ASK" : textEnum(approval, "approvalPolicy",
                Set.of("ASK", "NEVER"));
        JsonNode rules = input.get("toolRules");
        if (rules == null || rules.isNull()) {
            return new Permissions(permissionMode, approvalPolicy, List.of());
        }
        if (!rules.isArray() || rules.size() > 64) {
            throw invalid("toolRules must be an array with at most 64 entries.");
        }
        List<ToolRule> parsed = new ArrayList<>();
        for (JsonNode rule : rules) {
            parsed.add(toolRule(rule));
        }
        return new Permissions(permissionMode, approvalPolicy, List.copyOf(parsed));
    }

    private static ToolRule toolRule(JsonNode rule) {
        if (rule == null || !rule.isObject()) {
            throw invalid("Each tool rule must be an object.");
        }
        Set<String> allowed = Set.of("tool", "decision", "commandRegex", "pathGlob");
        for (String key : rule.propertyNames()) {
            if (!allowed.contains(key)) {
                throw invalid("A tool rule contains the unsupported field " + key + ".");
            }
        }
        String tool = text(rule, "tool");
        String decision = text(rule, "decision");
        String commandRegex = optionalText(rule, "commandRegex");
        String pathGlob = optionalText(rule, "pathGlob");
        try {
            return new ToolRule(tool, pathGlob, commandRegex, decision);
        } catch (IllegalArgumentException unsafe) {
            throw invalid("A tool rule is invalid: " + unsafe.getMessage());
        }
    }

    private static String textEnum(JsonNode value, String field, Set<String> supported) {
        if (!value.isString() || !supported.contains(value.stringValue())) {
            throw invalid(field + " must be one of " + String.join(", ", supported) + ".");
        }
        return value.stringValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || value.stringValue().length() > 256) {
            throw invalid("A tool rule " + field + " must be a bounded non-blank string.");
        }
        return value.stringValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return text(node, field);
    }
}
