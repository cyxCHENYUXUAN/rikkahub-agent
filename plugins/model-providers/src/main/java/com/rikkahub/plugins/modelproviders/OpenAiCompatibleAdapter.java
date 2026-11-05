package com.rikkahub.plugins.modelproviders;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.message.ImageBlock;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.message.MessageRole;
import com.rikkahub.agent.message.StreamChunk;
import com.rikkahub.agent.message.TextBlock;
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

/**
 * Streaming Chat Completions adapter. Requests stay frozen; credentials are read
 * from the supplier for each subscription and only placed in the HTTP header.
 * The host owns the injected client, which must have redirects disabled. Closing
 * this adapter silently closes its streams and releases its own executors.
 */
public final class OpenAiCompatibleAdapter implements LlmAdapter, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> STRUCTURAL_OPTIONS = Set.of("model", "messages", "tools", "stream",
            "stream_options", "n", "temperature", "top_p", "max_tokens", "stop", "metadata",
            "api_key", "apiKey", "authorization", "Authorization", "headers", "base_url");
    private final HttpClient client;
    private final URI endpoint;
    private final ProviderInfo provider;
    private final Supplier<String> apiKey;
    private final List<ModelInfo> models;
    private final Duration timeout;
    private final long timeoutMillis;
    private final ThreadPoolExecutor io;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Set<HttpStream> streams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OpenAiCompatibleAdapter(HttpClient client, URI baseUrl, String providerId,
                                   Supplier<String> apiKey, List<ModelInfo> models) {
        this(client, baseUrl, providerId, apiKey, models, DEFAULT_TIMEOUT);
    }

    public OpenAiCompatibleAdapter(HttpClient client, URI baseUrl, String providerId,
                                   Supplier<String> apiKey, List<ModelInfo> models, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("model client must disable automatic redirects");
        }
        this.endpoint = endpoint(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.models = List.copyOf(Objects.requireNonNull(models, "models"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            this.timeoutMillis = Math.max(1, timeout.toMillis());
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("timeout is too large");
        }
        EnumSet<ModelCapability> capabilities = EnumSet.of(ModelCapability.TEXT,
                ModelCapability.VISION, ModelCapability.STREAMING);
        if (this.models.stream().anyMatch(ModelInfo::supportsTools)) {
            capabilities.add(ModelCapability.TOOLS);
        }
        if (this.models.stream().anyMatch(ModelInfo::supportsReasoning)) {
            capabilities.add(ModelCapability.REASONING);
        }
        this.provider = new ProviderInfo(providerId, providerId, capabilities);
        this.io = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonThreads("model-provider-io"));
        this.deadlines = new ScheduledThreadPoolExecutor(1, daemonThreads("model-provider-deadline"));
        this.deadlines.setRemoveOnCancelPolicy(true);
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
            throw new IllegalStateException("model adapter is closed");
        }
        if (!provider.id().equals(request.model().provider())) {
            throw new IllegalArgumentException("request provider does not match adapter");
        }
        HttpStream stream = new HttpStream(request, signal);
        streams.add(stream);
        if (closed.get()) {
            stream.close();
        }
        return stream;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            streams.forEach(HttpStream::close);
            io.shutdownNow();
            deadlines.shutdownNow();
        }
    }

    private final class HttpStream implements LlmStream {
        private final LlmRequest request;
        private final CancellationToken signal;
        private final Object callbacks = new Object();
        private final AtomicReference<InputStream> body = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<?>> exchange = new AtomicReference<>();
        private final AtomicReference<Future<?>> worker = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();
        private final AtomicReference<AutoCloseable> cancellation = new AtomicReference<>();
        private volatile Thread workerThread;
        private volatile boolean stopped;
        private volatile boolean timedOut;
        private boolean disposed;
        private boolean subscribed;
        private StreamListener listener;

        HttpStream(LlmRequest request, CancellationToken signal) {
            this.request = request;
            this.signal = signal;
        }

        @Override
        public void subscribe(StreamListener value) {
            Objects.requireNonNull(value, "listener");
            synchronized (callbacks) {
                if (subscribed) {
                    throw new IllegalStateException("model stream supports one subscription");
                }
                subscribed = true;
                if (disposed) {
                    return;
                }
                listener = value;
            }
            cancellation.set(signal.onCancel(this::cancel));
            if (!active()) {
                release();
                return;
            }
            try {
                deadline.set(deadlines.schedule(this::expire,
                        timeoutMillis, TimeUnit.MILLISECONDS));
                if (!active()) {
                    release();
                    return;
                }
                worker.set(io.submit(this::run));
                if (!active()) {
                    release();
                }
            } catch (RejectedExecutionException ex) {
                terminate(RetryClassifier.overloaded(), false);
            }
        }

        private boolean active() {
            return !stopped;
        }

        private void expire() {
            timedOut = true;
            // The shared timer only unblocks I/O. Subscriber callbacks stay on
            // this stream's worker so one consumer cannot hold every deadline.
            CompletableFuture<?> pending = exchange.getAndSet(null);
            if (pending != null) {
                pending.cancel(true);
            }
            closeQuietly(body.getAndSet(null));
        }

        private void run() {
            workerThread = Thread.currentThread();
            try {
                if (!active()) {
                    return;
                }
                if (timedOut) {
                    terminate(RetryClassifier.timeout(), false);
                    return;
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(serialize(request), StandardCharsets.UTF_8));
                String key = apiKey.get();
                if (key != null && !key.isBlank()) {
                    builder.header("Authorization", "Bearer " + key.trim());
                }
                if (!active() || timedOut) {
                    return;
                }
                CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                exchange.set(pending);
                if (!active() || timedOut) {
                    pending.cancel(true);
                    return;
                }
                HttpResponse<InputStream> response = pending.get();
                body.set(response.body());
                if (!active() || timedOut) {
                    return;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    terminate(RetryClassifier.http(response.statusCode()), false);
                    return;
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.isEmpty() && !contentType.toLowerCase(Locale.ROOT).split(";", 2)[0]
                        .trim().equals("text/event-stream")) {
                    throw new StreamAssembler.ProtocolException();
                }
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                new StreamAssembler(this::chunk).read(new InputStreamReader(response.body(), decoder), () -> active() && !timedOut);
                if (active()) {
                    terminate(timedOut ? RetryClassifier.timeout() : null, false);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                terminate(timedOut ? RetryClassifier.timeout() : RetryClassifier.classify(ex), false);
            } catch (Exception ex) {
                terminate(timedOut ? RetryClassifier.timeout() : RetryClassifier.classify(ex), false);
            } finally {
                if (timedOut) {
                    terminate(RetryClassifier.timeout(), false);
                }
                release();
            }
        }

        private void chunk(StreamChunk chunk) {
            synchronized (callbacks) {
                if (!stopped && !disposed) {
                    listener.onChunk(chunk);
                }
            }
        }

        private void cancel() {
            terminate(null, true);
        }

        private void terminate(LlmFailure failure, boolean cancelled) {
            synchronized (callbacks) {
                if (stopped || disposed) {
                    return;
                }
                stopped = true;
            }
            release();
            synchronized (callbacks) {
                if (disposed) {
                    return;
                }
                try {
                    if (cancelled) {
                        try {
                            listener.onChunk(new StreamChunk.Cancelled());
                        } catch (RuntimeException ignored) {
                            // A consumer failure must not prevent the terminal callback.
                        }
                    }
                    if (!disposed) {
                        if (failure == null) {
                            listener.onComplete();
                        } else {
                            listener.onFailure(failure);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Terminal callbacks are never retried, including when a consumer throws.
                }
            }
        }

        @Override
        public void close() {
            synchronized (callbacks) {
                disposed = true;
                stopped = true;
            }
            release();
        }

        private void release() {
            closeQuietly(body.getAndSet(null));
            CompletableFuture<?> pending = exchange.getAndSet(null);
            if (pending != null) {
                pending.cancel(true);
            }
            Future<?> task = worker.getAndSet(null);
            if (task != null && workerThread != Thread.currentThread()) {
                task.cancel(true);
            }
            ScheduledFuture<?> timer = deadline.getAndSet(null);
            if (timer != null) {
                timer.cancel(false);
            }
            closeQuietly(cancellation.getAndSet(null));
            if (stopped) {
                streams.remove(this);
            }
        }
    }

    private static String serialize(LlmRequest request) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        request.generation().extra().forEach((key, value) -> {
            if (!STRUCTURAL_OPTIONS.contains(key)) {
                root.set(key, JSON.valueToTree(value));
            }
        });
        root.put("model", request.model().model());
        root.put("stream", true);
        root.put("n", 1);
        root.put("temperature", request.generation().temperature());
        root.put("top_p", request.generation().topP());
        if (!root.has("max_completion_tokens")) {
            root.put("max_tokens", request.generation().maxOutputTokens());
        }
        ObjectNode streamOptions = root.putObject("stream_options");
        Object extraStreamOptions = request.generation().extra().get("stream_options");
        if (extraStreamOptions != null) {
            JsonNode extra = JSON.valueToTree(extraStreamOptions);
            if (extra.isObject()) {
                extra.properties().forEach(entry -> streamOptions.set(entry.getKey(), entry.getValue()));
            }
        }
        streamOptions.put("include_usage", true);
        if (!request.generation().stopSequences().isEmpty()) {
            root.set("stop", JSON.valueToTree(request.generation().stopSequences()));
        }
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        for (Message message : request.messages()) {
            message(messages, message);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            request.tools().forEach(schema -> {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", schema.name());
                if (schema.description() != null) {
                    function.put("description", schema.description());
                }
                function.set("parameters", schema.parameters());
            });
        }
        return JSON.writeValueAsString(root);
    }

    private static void message(ArrayNode messages, Message message) {
        if (message.role() == MessageRole.TOOL) {
            if (message.content().isEmpty()) {
                throw new IllegalArgumentException("tool message requires a tool result");
            }
            for (ContentBlock block : message.content()) {
                if (!(block instanceof ToolResultBlock result)) {
                    throw new IllegalArgumentException("tool message requires tool result blocks");
                }
                String text = text(result.content());
                messages.addObject().put("role", "tool").put("tool_call_id", result.callId()).put("content", text);
            }
            return;
        }
        ObjectNode wire = messages.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT));
        ArrayNode calls = JSON.createArrayNode();
        boolean images = message.content().stream().anyMatch(ImageBlock.class::isInstance);
        if (images && message.role() != MessageRole.USER) {
            throw new IllegalArgumentException("images require a user message");
        }
        ArrayNode parts = JSON.createArrayNode();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock text) {
                parts.addObject().put("type", "text").put("text", text.text());
            } else if (block instanceof ImageBlock image) {
                parts.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:" + image.mediaType() + ";base64," + image.base64Data());
            } else if (block instanceof ToolCallBlock call && message.role() == MessageRole.ASSISTANT) {
                calls.addObject().put("id", call.callId()).put("type", "function").putObject("function")
                        .put("name", call.toolName()).put("arguments", call.argumentsJson());
            } else {
                throw new IllegalArgumentException("unsupported message content for role");
            }
        }
        if (images) {
            wire.set("content", parts);
        } else {
            wire.put("content", message.text());
        }
        if (!calls.isEmpty()) {
            wire.set("tool_calls", calls);
        }
    }

    private static String text(List<ContentBlock> content) {
        if (content.stream().anyMatch(block -> !(block instanceof TextBlock))) {
            throw new IllegalArgumentException("tool results require text content");
        }
        return content.stream().map(TextBlock.class::cast).map(TextBlock::text).collect(Collectors.joining("\n"));
    }

    private static URI endpoint(URI base) {
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !("http".equalsIgnoreCase(base.getScheme())
                || "https".equalsIgnoreCase(base.getScheme()))) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL without credentials, query or fragment");
        }
        String value = base.toASCIIString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String path = base.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            value += "/v1/chat/completions";
        } else if (!value.endsWith("/chat/completions")) {
            value += "/chat/completions";
        }
        return URI.create(value);
    }

    private static ThreadFactory daemonThreads(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Cleanup never exposes transport errors, headers or provider response bodies.
            }
        }
    }
}
