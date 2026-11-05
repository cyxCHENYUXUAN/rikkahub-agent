package com.rikkahub.plugins.mcpclient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.port.McpPort;
import com.rikkahub.agent.port.McpToolDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 2025-11-25 Stdio transport client.
 * Connects to a local child process over standard input / output pipes using JSON-RPC 2.0.
 */
public final class StdioMcpClient implements McpPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");

    private final String server;
    private final List<String> command;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-stdio-timeout");
        t.setDaemon(true);
        return t;
    });

    private final Object lifecycleLock = new Object();
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread readThread;
    private Thread errThread;

    private volatile boolean closed;
    private volatile String negotiatedVersion = "2025-11-25";
    private volatile List<McpToolDescriptor> cachedTools;

    public StdioMcpClient(String server, List<String> command) {
        this(server, command, null, Map.of(), Duration.ofSeconds(60));
    }

    public StdioMcpClient(String server, List<String> command, Path workingDirectory,
                          Map<String, String> environment, Duration timeout) {
        this.server = Objects.requireNonNull(server, "server");
        McpNames.tool(server, "probe");
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        this.command = List.copyOf(command);
        this.workingDirectory = workingDirectory;
        this.environment = environment != null ? Map.copyOf(environment) : Map.of();
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
        if (this.timeout.isNegative() || this.timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private void ensureStarted() {
        if (closed) {
            throw new IllegalStateException("mcp_stdio_closed");
        }
        if (process != null && process.isAlive()) {
            return;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("mcp_stdio_closed");
            }
            if (process != null && process.isAlive()) {
                return;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                if (workingDirectory != null) {
                    pb.directory(workingDirectory.toFile());
                }
                if (!environment.isEmpty()) {
                    pb.environment().putAll(environment);
                }
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                pb.redirectError(ProcessBuilder.Redirect.PIPE);

                process = pb.start();
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

                // Drain stderr in background to prevent child process deadlocking on pipe buffer
                InputStream stderr = process.getErrorStream();
                errThread = new Thread(() -> {
                    try {
                        byte[] buf = new byte[1024];
                        while (!closed && stderr.read(buf) != -1) {
                            // discard stderr or debug
                        }
                    } catch (IOException ignored) {
                    }
                }, "mcp-stdio-err-" + server);
                errThread.setDaemon(true);
                errThread.start();

                // Reading stdout lines for JSON-RPC messages
                readThread = new Thread(this::pumpStdout, "mcp-stdio-read-" + server);
                readThread.setDaemon(true);
                readThread.start();
            } catch (IOException e) {
                close();
                throw new IllegalStateException("mcp_process_launch_failed: " + e.getMessage(), e);
            }
        }
    }

    private void pumpStdout() {
        try {
            String line;
            while (!closed && (line = stdout.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode message = JSON.readTree(line);
                    if (!message.isObject()) {
                        continue;
                    }
                    if (message.has("id") && message.get("id").isIntegralNumber()) {
                        long id = message.get("id").longValue();
                        CompletableFuture<JsonNode> future = pending.remove(id);
                        if (future != null) {
                            if (message.has("error") && !message.get("error").isNull()) {
                                future.completeExceptionally(new IllegalStateException(
                                        "mcp_rpc_error: " + message.get("error").toString()));
                            } else if (message.has("result")) {
                                future.complete(message.get("result"));
                            } else {
                                future.completeExceptionally(new IllegalStateException("mcp_missing_result"));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore non-json or malformed log lines from stdout
                }
            }
        } catch (IOException ignored) {
        } finally {
            failPendingRequests("mcp_stdio_stream_closed");
        }
    }

    private void failPendingRequests(String reason) {
        IllegalStateException ex = new IllegalStateException(reason);
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(ex);
        }
        pending.clear();
    }

    @Override
    public synchronized List<McpToolDescriptor> tools() {
        if (closed) {
            throw new IllegalStateException("mcp_stdio_closed");
        }
        if (cachedTools != null) {
            return cachedTools;
        }
        ensureStarted();
        try {
            JsonNode init = request("initialize", Map.of(
                    "protocolVersion", negotiatedVersion,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "RikkaHub", "version", "1.0")
            ), CancellationToken.none());

            String negotiated = init.path("protocolVersion").asString();
            if (!SUPPORTED_VERSIONS.contains(negotiated)) {
                throw new IllegalStateException("mcp_unsupported_protocol_version: " + negotiated);
            }
            negotiatedVersion = negotiated;

            notify("notifications/initialized", Map.of());

            if (!init.path("capabilities").has("tools")) {
                cachedTools = List.of();
                return cachedTools;
            }

            List<McpToolDescriptor> descriptors = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            do {
                JsonNode page = request("tools/list",
                        cursor == null ? Map.of() : Map.of("cursor", cursor),
                        CancellationToken.none());
                if (!page.path("tools").isArray()) {
                    throw new IllegalStateException("mcp_invalid_tool_list");
                }
                for (JsonNode tool : page.path("tools")) {
                    String name = tool.path("name").asString();
                    McpNames.tool(server, name);
                    JsonNode schema = tool.get("inputSchema");
                    if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asString())
                            || !names.add(name) || descriptors.size() >= 256) {
                        throw new IllegalStateException("mcp_invalid_tool_schema");
                    }
                    descriptors.add(new McpToolDescriptor(server, name, tool.path("description").asString(""),
                            JSON.writeValueAsString(schema)));
                }
                cursor = page.hasNonNull("nextCursor") ? page.path("nextCursor").asString() : null;
                if (cursor != null && (cursor.isBlank() || !cursors.add(cursor) || cursors.size() > 32)) {
                    throw new IllegalStateException("mcp_invalid_pagination");
                }
            } while (cursor != null);

            cachedTools = List.copyOf(descriptors);
            return cachedTools;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("mcp_interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("mcp_connection_failed", failure);
        }
    }

    @Override
    public String call(String tool, String argumentsJson) throws Exception {
        return call(tool, argumentsJson, CancellationToken.none());
    }

    @Override
    public String call(String tool, String argumentsJson, CancellationToken signal) throws Exception {
        if (closed) {
            throw new IllegalStateException("mcp_stdio_closed");
        }
        signal.throwIfCancelled();
        if (tools().stream().noneMatch(t -> t.tool().equals(tool))) {
            throw new IllegalArgumentException("mcp_tool_not_found");
        }
        JsonNode args = JSON.readTree(argumentsJson);
        if (!args.isObject()) {
            throw new IllegalArgumentException("mcp_arguments_must_be_object");
        }
        return JSON.writeValueAsString(request("tools/call", Map.of("name", tool, "arguments", args), signal));
    }

    private JsonNode request(String method, Object params, CancellationToken signal) throws Exception {
        ensureStarted();
        signal.throwIfCancelled();
        long id = ids.incrementAndGet();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        AtomicBoolean cancelled = new AtomicBoolean();
        AutoCloseable onCancel = signal.onCancel(() -> {
            cancelled.set(true);
            pending.remove(id);
            future.cancel(true);
            notify("notifications/cancelled", Map.of("requestId", id));
        });

        var timeoutTask = scheduler.schedule(() -> {
            pending.remove(id);
            future.completeExceptionally(new TimeoutException("mcp_stdio_timeout"));
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        try {
            Map<String, Object> req = Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "method", method,
                    "params", params
            );
            sendLine(JSON.writeValueAsString(req));
            return future.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("mcp_request_failed", cause);
        } finally {
            timeoutTask.cancel(false);
            try {
                onCancel.close();
            } catch (Exception ignored) {
            }
            pending.remove(id);
        }
    }

    private void notify(String method, Object params) {
        if (closed) {
            return;
        }
        try {
            Map<String, Object> notification = Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params
            );
            sendLine(JSON.writeValueAsString(notification));
        } catch (Exception ignored) {
        }
    }

    private synchronized void sendLine(String line) throws IOException {
        if (closed || stdin == null) {
            throw new IOException("mcp_stdio_closed");
        }
        stdin.write(line);
        stdin.write("\n");
        stdin.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            scheduler.shutdownNow();
            failPendingRequests("mcp_stdio_closed");

            try {
                if (stdin != null) {
                    stdin.close();
                }
            } catch (IOException ignored) {
            }

            try {
                if (stdout != null) {
                    stdout.close();
                }
            } catch (IOException ignored) {
            }

            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
