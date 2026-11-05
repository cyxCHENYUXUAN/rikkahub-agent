package com.rikkahub.plugins.modelproviders;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.message.FinishReason;
import com.rikkahub.agent.message.ImageBlock;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.message.MessageRole;
import com.rikkahub.agent.message.StreamChunk;
import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.message.ToolCallBlock;
import com.rikkahub.agent.message.ToolResultBlock;
import com.rikkahub.agent.model.LlmAdapter;
import com.rikkahub.agent.model.LlmFailure;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.model.LlmStream;
import com.rikkahub.agent.model.ModelCapability;
import com.rikkahub.agent.model.ModelInfo;
import com.rikkahub.agent.model.ProviderInfo;
import com.rikkahub.agent.model.StreamListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini native streaming adapter (streamGenerateContent with alt=sse).
 * Supports text, thinking/reasoning parts, FunctionCall, and multi-modal images.
 */
public final class GeminiNativeAdapter implements LlmAdapter, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient client;
    private final URI baseUrl;
    private final ProviderInfo provider;
    private final Supplier<String> apiKey;
    private final List<ModelInfo> models;
    private final Duration timeout;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gemini-io");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gemini-deadline");
        t.setDaemon(true);
        return t;
    });
    private final Set<GeminiStream> activeStreams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public GeminiNativeAdapter(HttpClient client, URI baseUrl, String providerId,
                               Supplier<String> apiKey, List<ModelInfo> models) {
        this(client, baseUrl, providerId, apiKey, models, DEFAULT_TIMEOUT);
    }

    public GeminiNativeAdapter(HttpClient client, URI baseUrl, String providerId,
                               Supplier<String> apiKey, List<ModelInfo> models, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("model client must disable automatic redirects");
        }
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.models = List.copyOf(Objects.requireNonNull(models, "models"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.provider = new ProviderInfo(providerId != null ? providerId : "gemini",
                "Google Gemini", EnumSet.of(ModelCapability.STREAMING, ModelCapability.TOOLS, ModelCapability.REASONING));
    }

    private URI resolveEndpoint(String model) {
        String base = baseUrl.toString().replaceAll("/+$", "");
        if (!base.contains("/v1beta") && !base.contains("/v1")) {
            base = base + "/v1beta";
        }
        return URI.create(base + "/models/" + model + ":streamGenerateContent?alt=sse");
    }

    @Override
    public ProviderInfo provider() {
        return provider;
    }

    @Override
    public List<ModelInfo> models() {
        return models;
    }

    @Override
    public LlmStream stream(LlmRequest request, CancellationToken signal) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(signal, "signal");
        if (closed.get()) {
            throw new IllegalStateException("gemini_adapter_closed");
        }
        GeminiStream stream = new GeminiStream(request, signal);
        activeStreams.add(stream);
        return stream;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            deadlines.shutdownNow();
            io.shutdownNow();
            for (GeminiStream stream : activeStreams) {
                stream.close();
            }
            activeStreams.clear();
        }
    }

    private final class GeminiStream implements LlmStream {
        private final LlmRequest request;
        private final CancellationToken signal;
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final AtomicReference<HttpResponse<InputStream>> httpResponse = new AtomicReference<>();
        private volatile boolean terminated;

        private GeminiStream(LlmRequest request, CancellationToken signal) {
            this.request = request;
            this.signal = signal;
        }

        @Override
        public void subscribe(StreamListener listener) {
            Objects.requireNonNull(listener, "listener");
            if (!subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("stream_already_subscribed");
            }
            if (signal.isCancelled() || closed.get()) {
                terminated = true;
                activeStreams.remove(this);
                listener.onFailure(new LlmFailure("cancelled", "Request was cancelled", false, null, Map.of()));
                return;
            }

            io.execute(() -> runSubscription(listener));
        }

        private void runSubscription(StreamListener listener) {
            AutoCloseable cancelRegistration = signal.onCancel(this::close);
            var deadlineTask = deadlines.schedule(this::close, timeout.toMillis(), TimeUnit.MILLISECONDS);

            try {
                String payload = buildRequestBody(request);
                String key = apiKey.get();
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("missing_gemini_api_key");
                }

                URI endpoint = resolveEndpoint(request.model().model());
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .header("x-goog-api-key", key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .timeout(timeout)
                        .build();

                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                httpResponse.set(resp);

                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("http_" + resp.statusCode());
                }

                parseSseStream(resp.body(), listener);

            } catch (Exception e) {
                if (!terminated) {
                    terminated = true;
                    LlmFailure failure = RetryClassifier.classify(e);
                    listener.onFailure(failure);
                }
            } finally {
                terminated = true;
                deadlineTask.cancel(false);
                try {
                    cancelRegistration.close();
                } catch (Exception ignored) {
                }
                activeStreams.remove(this);
                close();
            }
        }

        private void parseSseStream(InputStream body, StreamListener listener) throws Exception {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String line;
            StringBuilder eventData = new StringBuilder();
            AtomicInteger toolIndex = new AtomicInteger();
            AtomicReference<FinishReason> finishReason = new AtomicReference<>(FinishReason.STOP);

            while (!terminated && !signal.isCancelled() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    if (eventData.length() > 0) {
                        processSseData(eventData.toString(), listener, toolIndex, finishReason);
                        eventData.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append("\n");
                    }
                    eventData.append(line.substring(5).trim());
                }
            }

            if (!terminated) {
                terminated = true;
                listener.onChunk(new StreamChunk.Finished(finishReason.get()));
                listener.onComplete();
            }
        }

        private void processSseData(String data, StreamListener listener,
                                    AtomicInteger toolIndex,
                                    AtomicReference<FinishReason> finishReason) throws Exception {
            if ("[DONE]".equals(data)) {
                return;
            }
            JsonNode root = JSON.readTree(data);

            if (root.has("candidates") && root.path("candidates").isArray()) {
                for (JsonNode candidate : root.path("candidates")) {
                    String reason = candidate.path("finishReason").asString("");
                    if ("STOP".equals(reason)) {
                        finishReason.set(FinishReason.STOP);
                    } else if ("MAX_TOKENS".equals(reason)) {
                        finishReason.set(FinishReason.LENGTH);
                    } else if ("SAFETY".equals(reason) || "RECITATION".equals(reason)) {
                        finishReason.set(FinishReason.CONTENT_FILTER);
                    }

                    JsonNode content = candidate.path("content");
                    if (content.has("parts") && content.path("parts").isArray()) {
                        for (JsonNode part : content.path("parts")) {
                            if (part.path("thought").asBoolean(false) || (part.has("thought") && part.get("thought").isTextual())) {
                                String thoughtText = part.has("thought") && part.get("thought").isTextual()
                                        ? part.get("thought").asString()
                                        : part.path("text").asString("");
                                if (!thoughtText.isEmpty()) {
                                    listener.onChunk(new StreamChunk.ReasoningDelta(thoughtText));
                                }
                            } else if (part.has("text")) {
                                String text = part.path("text").asString("");
                                if (!text.isEmpty()) {
                                    listener.onChunk(new StreamChunk.TextDelta(text));
                                }
                            }

                            if (part.has("functionCall")) {
                                finishReason.set(FinishReason.TOOL_CALLS);
                                JsonNode fc = part.path("functionCall");
                                String name = fc.path("name").asString();
                                JsonNode args = fc.path("args");
                                String argsJson = args.isObject() ? JSON.writeValueAsString(args) : "{}";
                                int idx = toolIndex.getAndIncrement();
                                listener.onChunk(new StreamChunk.ToolCallDelta(idx, "call_" + idx, name, argsJson));
                            }
                        }
                    }
                }
            }

            if (root.has("usageMetadata")) {
                JsonNode usage = root.path("usageMetadata");
                int promptTokens = usage.path("promptTokenCount").asInt(0);
                int candidatesTokens = usage.path("candidatesTokenCount").asInt(0);
                listener.onChunk(new StreamChunk.Usage(TokenUsage.of(promptTokens, candidatesTokens)));
            }

            if (root.has("error")) {
                String message = root.path("error").path("message").asString("gemini_api_error");
                throw new IllegalStateException("gemini_error: " + message);
            }
        }

        @Override
        public void close() {
            terminated = true;
            HttpResponse<InputStream> resp = httpResponse.get();
            if (resp != null) {
                try {
                    resp.body().close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String buildRequestBody(LlmRequest request) {
        ObjectNode root = JSON.createObjectNode();

        // System instruction
        StringBuilder systemBuilder = new StringBuilder();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            systemBuilder.append(request.systemPrompt());
        }
        for (Message msg : request.messages()) {
            if (msg.role() == MessageRole.SYSTEM) {
                for (ContentBlock block : msg.content()) {
                    if (block instanceof TextBlock tb) {
                        if (systemBuilder.length() > 0) {
                            systemBuilder.append("\n\n");
                        }
                        systemBuilder.append(tb.text());
                    }
                }
            }
        }
        if (systemBuilder.length() > 0) {
            ObjectNode sysInst = root.putObject("systemInstruction");
            ArrayNode parts = sysInst.putArray("parts");
            parts.addObject().put("text", systemBuilder.toString());
        }

        // Contents array
        ArrayNode contentsNode = root.putArray("contents");
        for (Message msg : request.messages()) {
            if (msg.role() == MessageRole.SYSTEM) {
                continue;
            }
            ObjectNode turnNode = JSON.createObjectNode();
            turnNode.put("role", msg.role() == MessageRole.USER ? "user" : "model");
            ArrayNode partsNode = turnNode.putArray("parts");

            for (ContentBlock block : msg.content()) {
                if (block instanceof TextBlock tb) {
                    partsNode.addObject().put("text", tb.text());
                } else if (block instanceof ImageBlock ib) {
                    ObjectNode part = partsNode.addObject();
                    ObjectNode inline = part.putObject("inlineData");
                    inline.put("mimeType", ib.mediaType());
                    inline.put("data", ib.base64Data());
                } else if (block instanceof ToolCallBlock tcb) {
                    ObjectNode part = partsNode.addObject();
                    ObjectNode fc = part.putObject("functionCall");
                    fc.put("name", tcb.toolName());
                    try {
                        fc.set("args", JSON.readTree(tcb.argumentsJson()));
                    } catch (Exception ignored) {
                        fc.set("args", JSON.createObjectNode());
                    }
                } else if (block instanceof ToolResultBlock trb) {
                    ObjectNode part = partsNode.addObject();
                    ObjectNode fr = part.putObject("functionResponse");
                    fr.put("name", trb.toolName());
                    ObjectNode respObj = fr.putObject("response");
                    String text = trb.content().stream()
                            .filter(TextBlock.class::isInstance)
                            .map(c -> ((TextBlock) c).text())
                            .collect(Collectors.joining("\n"));
                    respObj.put("result", text);
                    respObj.put("isError", trb.error());
                }
            }

            if (partsNode.size() > 0) {
                contentsNode.add(turnNode);
            }
        }

        // Tools declarations
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            ObjectNode toolWrap = toolsNode.addObject();
            ArrayNode funcDecls = toolWrap.putArray("functionDeclarations");
            for (var tool : request.tools()) {
                ObjectNode decl = funcDecls.addObject();
                decl.put("name", tool.name());
                if (tool.description() != null) {
                    decl.put("description", tool.description());
                }
                decl.set("parameters", tool.parameters());
            }
        }

        // Generation config
        ObjectNode genConfig = root.putObject("generationConfig");
        if (request.generation().temperature() > 0) {
            genConfig.put("temperature", request.generation().temperature());
        }
        if (request.generation().topP() > 0) {
            genConfig.put("topP", request.generation().topP());
        }
        if (request.generation().maxOutputTokens() > 0) {
            genConfig.put("maxOutputTokens", request.generation().maxOutputTokens());
        }
        if (!request.generation().stopSequences().isEmpty()) {
            ArrayNode stops = genConfig.putArray("stopSequences");
            for (String stop : request.generation().stopSequences()) {
                stops.add(stop);
            }
        }

        return root.toString();
    }
}
