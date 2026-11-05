package com.rikkahub.features.memory.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.model.ModelRef;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.port.SessionStore;
import com.rikkahub.features.chat.application.ConversationConfig;
import com.rikkahub.features.chat.application.ConversationPolicyService;
import com.rikkahub.features.chat.application.ModelTextCollector;
import com.rikkahub.features.providers.application.ProviderApplicationService;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.StoragePaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Manual memory extraction. Candidates go to data/memory/inbox and are never
 * indexed until the user reviews and saves them through the memory API.
 */
@Service
public final class MemoryExtractionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SessionStore sessions;
    private final ConversationPolicyService policies;
    private final ProviderApplicationService providers;
    private final IdGenerator ids;
    private final Clock clock;
    private final Path inbox;
    private final String prompt;

    public MemoryExtractionService(SessionStore sessions, ConversationPolicyService policies,
                                   ProviderApplicationService providers, IdGenerator ids, Clock clock,
                                   StoragePaths paths) {
        this.sessions = sessions;
        this.policies = policies;
        this.providers = providers;
        this.ids = ids;
        this.clock = clock;
        this.inbox = paths.dataDirectory().resolve("memory").resolve("inbox");
        try {
            this.prompt = new ClassPathResource("prompts/memory/extract.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("memory extraction prompt is unavailable", failure);
        }
    }

    public int extract(com.rikkahub.agent.session.SessionId sessionId) {
        var snapshot = sessions.load(sessionId);
        List<Message> history = snapshot.session().deriveMessages();
        if (history.isEmpty()) {
            return 0;
        }
        AgentConfigSnapshot config = ConversationConfig.freeze(policies.resolve(sessionId), clock);
        ModelRef model = config.model();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("m-memory-extract", prompt, clock.now()));
        int from = Math.max(0, history.size() - 40);
        messages.addAll(history.subList(from, history.size()));
        LlmRequest request = new LlmRequest(ids.newId("req"), model, null, messages, List.of(),
                config.generation(), Map.of("purpose", "memory_extract"), clock.now());
        ModelTextCollector.Result result = ModelTextCollector.collect(
                providers.adapter(model.provider()), request, CancellationToken.none(),
                Duration.ofMinutes(2));
        if (!result.succeeded()) {
            throw new ApiFailure(503, "MEMORY_EXTRACTION_FAILED", "Memory extraction failed.");
        }
        List<JsonNode> candidates = parse(result.text());
        try {
            Files.createDirectories(inbox);
            String stamp = clock.now().toString().replace(':', '-');
            int index = 0;
            for (JsonNode candidate : candidates) {
                String title = candidate.path("title").asString("").trim();
                String body = candidate.path("body").asString("").trim();
                if (title.isEmpty() || body.isEmpty()) continue;
                String id = sessionId.value() + "-" + stamp + "-" + (++index);
                Files.writeString(inbox.resolve(id + ".md"), "# " + title + "\n\n" + body + "\n",
                        StandardCharsets.UTF_8);
            }
            return index;
        } catch (IOException failure) {
            throw new ApiFailure(503, "MEMORY_STORAGE_UNAVAILABLE", "Memory candidates could not be stored.");
        }
    }

    private static List<JsonNode> parse(String text) {
        try {
            JsonNode root = JSON.readTree(text);
            if (!root.isArray()) {
                throw new IllegalArgumentException("memory extraction must return an array");
            }
            List<JsonNode> candidates = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.isObject()) candidates.add(item);
            }
            return candidates;
        } catch (RuntimeException invalid) {
            throw new ApiFailure(503, "MEMORY_EXTRACTION_INVALID", "Memory extraction returned invalid JSON.");
        }
    }
}
