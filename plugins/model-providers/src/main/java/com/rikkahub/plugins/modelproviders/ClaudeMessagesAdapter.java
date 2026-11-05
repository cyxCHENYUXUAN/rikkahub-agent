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
import java.util.HashMap;
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
 * Anthropic Claude /v1/messages native streaming adapter.
 * Supports text, thinking/reasoning blocks, tool use, and multi-modal images.
 */
public final class ClaudeMessagesAdapter implements LlmAdapter, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient client;
    private final URI endpoint;
    private final ProviderInfo provider;
    private final Supplier<String> apiKey;
    private final List<ModelInfo> models;
    private final Duration timeout;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "claude-io");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "claude-deadline");
        t.setDaemon(true);
        return t;
    });
    private final Set<ClaudeStream> activeStreams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ClaudeMessagesAdapter(HttpClient client, URI baseUrl, String providerId,
                                 Supplier<String> apiKey, List<ModelInfo> models) {
        this(client, baseUrl, providerId, apiKey, models, DEFAULT_TIMEOUT);
    }

    public ClaudeMessagesAdapter(HttpClient client, URI baseUrl, String providerId,
                                 Supplier<String> apiKey, List<ModelInfo> models, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("model client must disable automatic redirects");
        }
        this.endpoint = resolveEndpoint(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.models = List.copyOf(Objects.requireNonNull(models, "models"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.provider = new ProviderInfo(providerId != null ? providerId : "anthropic",
                "Anthropic Claude", EnumSet.of(ModelCapability.STREAMING, ModelCapability.TOOLS, ModelCapability.REASONING));
    }

    private static URI resolveEndpoint(URI baseUrl) {
        String uri = baseUrl.toString().replaceAll("/+$", "");
        if (!uri.endsWith("/v1/messages")) {
            uri = uri + "/v1/messages";
        }
        return URI.create(uri);
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
            throw new IllegalStateException("claude_adapter_closed");
        }
        ClaudeStream stream = new ClaudeStream(request, signal);
        activeStreams.add(stream);
        return stream;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            deadlines.shutdownNow();
            io.shutdownNow();
            for (ClaudeStream stream : activeStreams) {
                stream.close();
            }
            activeStreams.clear();
        }
    }

    private final class ClaudeStream implements LlmStream {
        private final LlmRequest request;
        private final CancellationToken signal;
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final AtomicReference<HttpResponse<InputStream>> httpResponse = new AtomicReference<>();
        private volatile boolean terminated;

        private ClaudeStream(LlmRequest request, CancellationToken signal) {
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
                    throw new IllegalStateException("missing_anthropic_api_key");
                }

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .header("x-api-key", key)
                        .header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
                        .header("content-type", "application/json")
                        .header("accept", "text/event-stream")
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
            String currentEvent = null;
            StringBuilder eventData = new StringBuilder();

            Map<Integer, String> toolCallIds = new HashMap<>();
            Map<Integer, String> toolCallNames = new HashMap<>();
            AtomicInteger promptTokens = new AtomicInteger();
            AtomicInteger completionTokens = new AtomicInteger();
            AtomicReference<FinishReason> finishReason = new AtomicReference<>(FinishReason.STOP);

            while (!terminated && !signal.isCancelled() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    if (currentEvent != null && eventData.length() > 0) {
                        processSseEvent(currentEvent, eventData.toString(), listener, toolCallIds,
                                toolCallNames, promptTokens, completionTokens, finishReason);
                    }
                    currentEvent = null;
                    eventData.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
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

        private void processSseEvent(String event, String data, StreamListener listener,
                                     Map<Integer, String> toolCallIds,
                                     Map<Integer, String> toolCallNames,
                                     AtomicInteger promptTokens,
                                     AtomicInteger completionTokens,
                                     AtomicReference<FinishReason> finishReason) throws Exception {
            if ("[DONE]".equals(data)) {
                return;
            }
            JsonNode root = JSON.readTree(data);

            switch (event) {
                case "message_start" -> {
                    JsonNode message = root.path("message");
                    JsonNode usage = message.path("usage");
                    int input = usage.path("input_tokens").asInt(0);
                    promptTokens.set(input);
                }
                case "content_block_start" -> {
                    int index = root.path("index").asInt(0);
                    JsonNode block = root.path("content_block");
                    String type = block.path("type").asString();
                    if ("tool_use".equals(type)) {
                        String id = block.path("id").asString();
                        String name = block.path("name").asString();
                        toolCallIds.put(index, id);
                        toolCallNames.put(index, name);
                    }
                }
                case "content_block_delta" -> {
                    int index = root.path("index").asInt(0);
                    JsonNode delta = root.path("delta");
                    String type = delta.path("type").asString();

                    if ("text_delta".equals(type)) {
                        String text = delta.path("text").asString("");
                        if (!text.isEmpty()) {
                            listener.onChunk(new StreamChunk.TextDelta(text));
                        }
                    } else if ("thinking_delta".equals(type)) {
                        String thinking = delta.path("thinking").asString("");
                        if (!thinking.isEmpty()) {
                            listener.onChunk(new StreamChunk.ReasoningDelta(thinking));
                        }
                    } else if ("input_json_delta".equals(type)) {
                        String fragment = delta.path("partial_json").asString("");
                        String id = toolCallIds.getOrDefault(index, "call_" + index);
                        String name = toolCallNames.getOrDefault(index, "unknown");
                        listener.onChunk(new StreamChunk.ToolCallDelta(index, id, name, fragment));
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = root.path("delta");
                    String stopReason = delta.path("stop_reason").asString();
                    if ("tool_use".equals(stopReason)) {
                        finishReason.set(FinishReason.TOOL_CALLS);
                    } else if ("max_tokens".equals(stopReason)) {
                        finishReason.set(FinishReason.LENGTH);
                    } else if ("end_turn".equals(stopReason)) {
                        finishReason.set(FinishReason.STOP);
                    }

                    JsonNode usage = root.path("usage");
                    int output = usage.path("output_tokens").asInt(0);
                    completionTokens.set(output);
                    listener.onChunk(new StreamChunk.Usage(TokenUsage.of(promptTokens.get(), output)));
                }
                case "error" -> {
                    String message = root.path("error").path("message").asString("claude_api_error");
                    throw new IllegalStateException("claude_error: " + message);
                }
                default -> {
                    // Ignore ping, content_block_stop, message_stop etc.
                }
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
        root.put("model", request.model().model());
        root.put("stream", true);

        int maxTokens = request.generation().maxOutputTokens();
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
        root.put("max_tokens", maxTokens);
        if (request.generation().temperature() > 0) {
            root.put("temperature", request.generation().temperature());
        }

        // System prompt extraction
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
            root.put("system", systemBuilder.toString());
        }

        // Messages array
        ArrayNode messagesNode = root.putArray("messages");
        for (Message msg : request.messages()) {
            if (msg.role() == MessageRole.SYSTEM) {
                continue;
            }
            ObjectNode msgNode = JSON.createObjectNode();
            msgNode.put("role", msg.role() == MessageRole.USER ? "user" : "assistant");
            ArrayNode contentNode = msgNode.putArray("content");

            for (ContentBlock block : msg.content()) {
                if (block instanceof TextBlock tb) {
                    ObjectNode b = contentNode.addObject();
                    b.put("type", "text");
                    b.put("text", tb.text());
                } else if (block instanceof ImageBlock ib) {
                    ObjectNode b = contentNode.addObject();
                    b.put("type", "image");
                    ObjectNode src = b.putObject("source");
                    src.put("type", "base64");
                    src.put("media_type", ib.mediaType());
                    src.put("data", ib.base64Data());
                } else if (block instanceof ToolCallBlock tcb) {
                    ObjectNode b = contentNode.addObject();
                    b.put("type", "tool_use");
                    b.put("id", tcb.callId());
                    b.put("name", tcb.toolName());
                    try {
                        b.set("input", JSON.readTree(tcb.argumentsJson()));
                    } catch (Exception ignored) {
                        b.set("input", JSON.createObjectNode());
                    }
                } else if (block instanceof ToolResultBlock trb) {
                    ObjectNode b = contentNode.addObject();
                    b.put("type", "tool_result");
                    b.put("tool_use_id", trb.callId());
                    String text = trb.content().stream()
                            .filter(TextBlock.class::isInstance)
                            .map(c -> ((TextBlock) c).text())
                            .collect(Collectors.joining("\n"));
                    b.put("content", text);
                    b.put("is_error", trb.error());
                }
            }
            if (contentNode.size() > 0) {
                messagesNode.add(msgNode);
            }
        }

        // Tools array
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (var tool : request.tools()) {
                ObjectNode tNode = toolsNode.addObject();
                tNode.put("name", tool.name());
                if (tool.description() != null) {
                    tNode.put("description", tool.description());
                }
                tNode.set("input_schema", tool.parameters());
            }
        }

        // Thinking configuration (for Claude 3.7+ with reasoning)
        Object thinkingExtra = request.generation().extra().get("thinking");
        Object budgetExtra = request.generation().extra().get("budget_tokens");
        if (Boolean.TRUE.equals(thinkingExtra) || budgetExtra != null) {
            int budget = budgetExtra instanceof Number n ? n.intValue() : 1024;
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", budget);
        }

        return root.toString();
    }
}
