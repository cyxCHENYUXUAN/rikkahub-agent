package com.rikkahub.features.providers.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.model.LlmAdapter;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.TransactionRunner;
import com.rikkahub.plugins.modelproviders.ClaudeMessagesAdapter;
import com.rikkahub.plugins.modelproviders.GeminiNativeAdapter;
import com.rikkahub.plugins.modelproviders.OpenAiCompatibleAdapter;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Provider configuration keeps credential references; only runtime suppliers resolve their values. */
@Service
public final class ProviderApplicationService implements AutoCloseable {
    private static final Set<String> FIELDS = Set.of("id", "name", "type", "baseUrl", "credentialRef");
    private static final Set<String> SUPPORTED_TYPES = Set.of("openai-compatible", "anthropic", "gemini");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CREDENTIAL = Pattern.compile("env:[A-Z_][A-Z0-9_]{0,127}");
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final Clock clock;
    private final IdGenerator ids;
    private final Environment environment;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<AdapterKey, LlmAdapter> adapters = new HashMap<>();
    private boolean closed;

    public ProviderApplicationService(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec,
                                       Clock clock, IdGenerator ids, Environment environment) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = codec;
        this.clock = clock;
        this.ids = ids;
        this.environment = environment;
    }

    /** Called by the idempotency owner inside its existing short transaction. */
    public ProviderView createInTransaction(JsonNode input) {
        requireTransaction();
        object(input);
        String id = optional(input, "id", 128);
        if (id == null) {
            id = ids.newId("provider");
        }
        identifier(id);
        String name = required(input, "name", 128);
        String type = optional(input, "type", 64);
        if (type == null) {
            type = "openai-compatible";
        }
        if (!SUPPORTED_TYPES.contains(type)) {
            throw invalid("type must be one of: " + SUPPORTED_TYPES);
        }
        String baseUrl = required(input, "baseUrl", 2048);
        baseUrl(baseUrl);
        String credentialRef = optional(input, "credentialRef", 132);
        if (credentialRef != null && !CREDENTIAL.matcher(credentialRef).matches()) {
            throw invalid("credentialRef must use a supported environment reference.");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM providers WHERE id=:id").param("id", id)
                .query(Integer.class).single() != 0) {
            throw conflict();
        }
        String at = EventCodec.timestamp(clock.now());
        try {
            jdbc.sql("""
                    INSERT INTO providers(id,name,type,base_url,credential_ref,config_json,enabled,version,created_at,updated_at)
                    VALUES(:id,:name,:type,:base,:credential,:config,1,0,:at,:at)
                    """).param("id", id).param("name", name).param("type", type).param("base", baseUrl)
                    .param("credential", credentialRef).param("config", codec.encode(Map.of())).param("at", at).update();
        } catch (DuplicateKeyException duplicate) {
            throw conflict();
        }
        return requireInTransaction(id);
    }

    public List<ProviderView> list() {
        return transactions.read(() -> jdbc.sql("SELECT * FROM providers ORDER BY created_at,id")
                .query((row, number) -> view(row)).list());
    }

    public ProviderView requireInTransaction(String id) {
        requireTransaction();
        identifier(id);
        return jdbc.sql("SELECT * FROM providers WHERE id=:id").param("id", id)
                .query((row, number) -> view(row)).optional()
                .orElseThrow(() -> new ApiFailure(404, "PROVIDER_NOT_FOUND", "The provider was not found."));
    }

    /** The database read finishes before any model I/O; each version has its own frozen adapter. */
    public LlmAdapter adapter(String id) {
        ProviderView provider = transactions.read(() -> requireInTransaction(id));
        if (!provider.enabled()) {
            throw new ApiFailure(503, "PROVIDER_DISABLED", "The selected provider is disabled.");
        }
        if (!SUPPORTED_TYPES.contains(provider.type())) {
            throw new ApiFailure(503, "PROVIDER_UNAVAILABLE", "The selected provider type is unavailable.");
        }
        credential(provider.credentialRef());
        synchronized (adapters) {
            if (closed) {
                throw new ApiFailure(503, "PROVIDER_UNAVAILABLE", "The model provider is unavailable.");
            }
            return adapters.computeIfAbsent(new AdapterKey(provider.id(), provider.version()), ignored -> {
                try {
                    URI uri = URI.create(provider.baseUrl());
                    return switch (provider.type()) {
                        case "openai-compatible" -> new OpenAiCompatibleAdapter(client, uri, provider.id(),
                                () -> credential(provider.credentialRef()), List.of());
                        case "anthropic" -> new ClaudeMessagesAdapter(client, uri, provider.id(),
                                () -> credential(provider.credentialRef()), List.of());
                        case "gemini" -> new GeminiNativeAdapter(client, uri, provider.id(),
                                () -> credential(provider.credentialRef()), List.of());
                        default -> throw new ApiFailure(503, "PROVIDER_UNAVAILABLE", "The selected provider type is unavailable.");
                    };
                } catch (IllegalArgumentException failure) {
                    throw new ApiFailure(503, "PROVIDER_UNAVAILABLE", "The selected provider configuration is unavailable.");
                }
            });
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (adapters) {
            if (!closed) {
                closed = true;
                for (LlmAdapter adapter : adapters.values()) {
                    if (adapter instanceof AutoCloseable closeable) {
                        try {
                            closeable.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
                adapters.clear();
            }
        }
    }

    private ProviderView view(ResultSet row) throws SQLException {
        String reference = row.getString("credential_ref");
        return new ProviderView(row.getString("id"), row.getString("name"), row.getString("type"),
                row.getString("base_url"), reference, configured(reference), row.getInt("enabled") == 1,
                row.getLong("version"));
    }

    private boolean configured(String reference) {
        if (reference == null) {
            return false;
        }
        try {
            return credential(reference) != null;
        } catch (ApiFailure unavailable) {
            return false;
        }
    }

    private String credential(String reference) {
        if (reference == null) {
            return null;
        }
        if (CREDENTIAL.matcher(reference).matches()) {
            try {
                String value = environment.getProperty(reference.substring(4));
                if (value != null && !value.isBlank()) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // Environment errors may contain a resolved secret or property source details.
            }
        }
        throw new ApiFailure(503, "PROVIDER_CREDENTIAL_UNAVAILABLE", "The provider credential is not configured.");
    }

    private static void object(JsonNode input) {
        if (input == null || !input.isObject()
                || input.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))) {
            throw invalid("Provider input contains unsupported fields or has an invalid shape.");
        }
    }

    private static String required(JsonNode input, String field, int limit) {
        String value = optional(input, field, limit);
        if (value == null) {
            throw invalid("A required provider field is missing.");
        }
        return value;
    }

    private static String optional(JsonNode input, String field, int limit) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalid("Provider text fields must contain strings.");
        }
        String text = value.stringValue().trim();
        if (text.isEmpty() || text.length() > limit) {
            throw invalid("A provider text field has an invalid length.");
        }
        return text;
    }

    private static void identifier(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw invalid("The provider ID has an invalid format.");
        }
    }

    private static void baseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // URI parser messages echo their input; expose a fixed validation detail instead.
        }
        throw invalid("baseUrl must be an HTTP(S) URL without credentials, query or fragment.");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("provider operation requires its caller's transaction");
        }
    }

    private static ApiFailure invalid(String detail) {
        return new ApiFailure(400, "INVALID_PROVIDER", detail);
    }

    private static ApiFailure conflict() {
        return new ApiFailure(409, "PROVIDER_CONFLICT", "A provider with this ID already exists.");
    }

    public record ProviderView(String id, String name, String type, String baseUrl, String credentialRef,
                               boolean credentialConfigured, boolean enabled, long version) {
    }

    private record AdapterKey(String id, long version) {
    }
}
