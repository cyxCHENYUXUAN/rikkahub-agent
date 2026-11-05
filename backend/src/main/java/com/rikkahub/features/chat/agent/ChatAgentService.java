package com.rikkahub.features.chat.agent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import com.rikkahub.agent.api.*;
import com.rikkahub.agent.approval.ApprovalPolicy;
import com.rikkahub.agent.approval.ApprovalService;
import com.rikkahub.agent.config.*;
import com.rikkahub.agent.loop.*;
import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.model.*;
import com.rikkahub.agent.port.*;
import com.rikkahub.agent.session.*;
import com.rikkahub.agent.tool.*;
import com.rikkahub.features.assistants.application.AssistantApplicationService.AssistantProfile;
import com.rikkahub.features.chat.application.ConversationConfig;
import com.rikkahub.features.chat.application.ConversationPolicyService;
import com.rikkahub.features.approvals.application.DurableApprovalBroker;
import com.rikkahub.features.providers.application.ProviderApplicationService;
import com.rikkahub.features.workspace.output.ToolOutputStore;
import com.rikkahub.features.workspace.output.ToolOutputStore.Publication;
import com.rikkahub.plugins.builtintools.BuiltinTools;
import com.rikkahub.plugins.workspaceruntimes.LocalExecutionEnvironment;
import com.rikkahub.plugins.workspaceruntimes.RuntimeLimits;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.sse.SseHub;
import com.rikkahub.platform.storage.StoragePaths;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * Product configuration stops here; the core receives only its own immutable
 * types.
 */
@Service
public final class ChatAgentService implements AutoCloseable {
    private static final int OUTPUT_SPILL_CHARS = 262144;
    private final DefaultAgentRuntime runtime;
    private final ExecutorService workers;
    private final DefaultToolPipeline pipeline;
    private final ConversationPolicyService policies;
    private final com.rikkahub.features.skills.application.SkillCatalog skillCatalog;
    private final com.rikkahub.agent.port.MemoryStore memory;
    private final com.rikkahub.agent.port.SearchPort search;
    private final com.rikkahub.features.mcp.application.McpCatalog mcp;
    private final com.rikkahub.features.memory.application.MemoryExtractionScheduler memoryScheduler;
    private final SessionStore sessions;
    private final StoragePaths paths;
    private final Clock clock;
    private final ToolOutputStore outputs;
    private final Map<SessionId, AgentHandle> handles = new LinkedHashMap<>();
    private final Map<SessionId, Set<Reservation>> reservations = new HashMap<>();
    private final Map<SessionId, Owned> environments = new LinkedHashMap<>();
    private final Map<SessionId, List<ToolDefinition>> sessionTools = new ConcurrentHashMap<>();
    private final Map<SessionId, WorkerPolicy> workerPolicies = new ConcurrentHashMap<>();
    private boolean closed;

    /**
     * Builtin tools are registered per session before the first model request.
     * The core registry keys on session/agent/cwd, but the agent id only exists
     * after resume, so the product owns this session-scoped view instead.
     */
    private final ToolRegistry registry = new ToolRegistry() {
        public List<ToolSchema> schemas(ToolScope scope) {
            return toolsFor(SessionId.of(scope.sessionId()))
                    .stream().map(ToolDefinition::schema).toList();
        }

        public Optional<ToolDefinition> find(String name, ToolScope scope) {
            return toolsFor(SessionId.of(scope.sessionId()))
                    .stream().filter(definition -> definition.schema().name().equals(name)).findFirst();
        }

        public AutoCloseable register(ToolDefinition tool, ToolScope scope) {
            throw new UnsupportedOperationException("builtin tools are registered per session by the host");
        }
    };

    public ChatAgentService(ProviderApplicationService providers, ConversationPolicyService policies,
            SessionStore sessions, InboxStore inbox, SseHub sse, StoragePaths paths, Clock clock,
            IdGenerator ids, DurableApprovalBroker broker, ToolOutputStore outputs,
            com.rikkahub.agent.context.CompactionService compaction,
            com.rikkahub.features.skills.application.SkillCatalog skillCatalog,
            com.rikkahub.agent.port.MemoryStore memory,
            com.rikkahub.agent.port.SearchPort search,
            com.rikkahub.features.memory.application.MemoryExtractionScheduler memoryScheduler,
            com.rikkahub.features.mcp.application.McpCatalog mcp) {
        this.policies = policies;
        this.skillCatalog = skillCatalog;
        this.memory = memory;
        this.search = search;
        this.memoryScheduler = memoryScheduler;
        this.mcp = mcp;
        this.sessions = sessions;
        this.paths = paths;
        this.clock = clock;
        this.outputs = outputs;
        workers = Executors.newFixedThreadPool(16, r -> {
            var thread = new Thread(r, "rikkahub-tool");
            thread.setDaemon(true);
            return thread;
        });
        pipeline = new DefaultToolPipeline(registry, new ApprovalService(broker), ApprovalPolicy.ASK, clock, ids,
                workers);
        LlmAdapter routing = new LlmAdapter() {
            public ProviderInfo provider() {
                return new ProviderInfo("configured", "Configured providers",
                        Set.of(ModelCapability.TEXT, ModelCapability.STREAMING));
            }

            public List<ModelInfo> models() {
                return List.of();
            }

            public LlmStream stream(LlmRequest request, CancellationToken signal) {
                return providers.adapter(request.model().provider()).stream(request, signal);
            }
        };
        runtime = new DefaultAgentRuntime(new RuntimeServices(sessions, inbox, routing, registry, pipeline, sse::emit,
                List.of(), memoryScheduler::onTurn, clock, ids, RetryPolicy.defaults(), this::resolveConfig,
                this::limitOutput, compaction));
    }

    /**
     * Promotes runtime capture spills into the product's session-bound durable
     * store,
     * then applies the inline text limit. Durable references must be persisted in
     * the
     * tool result event, not only in transient metadata.
     */
    private ToolResult limitOutput(String sessionId, ToolCall call, ToolResult result) {
        SessionId session = SessionId.of(sessionId);
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        List<com.rikkahub.agent.message.ContentBlock> content = new ArrayList<>(result.content());
        Object references = metadata.get("outputReferences");
        if (references instanceof Collection<?> known && !known.isEmpty()) {
            Owned owned = environment(session);
            List<String> durable = new ArrayList<>();
            for (Object value : known) {
                String reference = String.valueOf(value);
                if (reference.matches("out_[0-9a-f]{32}")) {
                    durable.add(reference);
                    continue;
                }
                if (owned == null)
                    return new ToolResult(List.of(new TextBlock(
                            "tool output is unavailable: the session environment is closed")), true, Map.of());
                Publication publication;
                try {
                    publication = outputs.promote(session, reference,
                            (ref, offset, max) -> owned.environment.readOutput(ref, offset, max));
                } catch (RuntimeException failure) {
                    return new ToolResult(List.of(new TextBlock(
                            "tool output is unavailable: durable promotion failed")), true, Map.of());
                }
                if (publication.reference() == null)
                    return new ToolResult(List.of(new TextBlock(
                            "tool output is unavailable: durable storage rejected the output")), true, Map.of());
                durable.add(publication.reference());
                String size = publication.sourceBytes() >= 0
                        ? publication.retainedBytes() + " bytes retained of " + publication.sourceBytes()
                        : publication.retainedBytes() + " bytes retained (source size unavailable)";
                content.add(new TextBlock(
                        "oversized tool output retained as " + publication.reference() + " (" + size + ")"));
            }
            metadata.put("outputReferences", List.copyOf(durable));
            result = new ToolResult(content, result.isError(), metadata);
        }
        if (result.content().size() != 1 || !(result.content().get(0) instanceof TextBlock text)
                || text.text().length() <= OUTPUT_SPILL_CHARS)
            return result;
        Publication publication = outputs.spill(session, text.text().getBytes(StandardCharsets.UTF_8));
        if (publication.reference() == null)
            return new ToolResult(List.of(new TextBlock(
                    "tool output is unavailable: durable storage rejected the output")), true, Map.of());
        String summary = "tool output exceeded the inline limit; durable reference " + publication.reference()
                + " (" + publication.retainedBytes() + " bytes retained of " + publication.sourceBytes() + " produced)";
        List<String> merged = new ArrayList<>();
        Object existing = metadata.get("outputReferences");
        if (existing instanceof Collection<?> refs)
            for (Object value : refs)
                merged.add(String.valueOf(value));
        merged.add(publication.reference());
        metadata.put("outputReferences", List.copyOf(merged));
        return new ToolResult(List.of(new TextBlock(summary)), result.isError(), Map.copyOf(metadata));
    }

    private synchronized Owned environment(SessionId id) {
        return environments.get(id);
    }

    private List<ToolDefinition> toolsFor(SessionId session) {
        List<ToolDefinition> tools = new ArrayList<>(sessionTools.getOrDefault(session, List.of()));
        tools.addAll(mcp.definitions().stream().map(tool -> guardWorker(session, tool)).toList());
        WorkerPolicy worker = workerPolicies.get(session);
        if (worker != null) tools.addAll(worker.additionalTools().stream().map(tool -> guardWorker(session, tool)).toList());
        return List.copyOf(tools);
    }

    /** Reserve capacity before the inbox transaction; pins even an idle writer. */
    public synchronized Reservation reserve(SessionId id) {
        ensureCapacity(id);
        Reservation reservation = new Reservation(id, UUID.randomUUID());
        reservations.computeIfAbsent(id, ignored -> new HashSet<>()).add(reservation);
        return reservation;
    }

    public synchronized void releaseReservation(Reservation reservation) {
        if (reservation == null)
            return;
        Set<Reservation> owned = reservations.get(reservation.session());
        if (owned != null && owned.remove(reservation) && owned.isEmpty())
            reservations.remove(reservation.session());
    }

    private void ensureCapacity(SessionId id) {
        if (closed)
            throw new ApiFailure(503, "SHUTTING_DOWN", "The application is shutting down.");
        if (handles.containsKey(id) || reservations.containsKey(id))
            return;
        if (occupiedSlots() >= 64)
            evictIdle();
        if (occupiedSlots() >= 64)
            throw new ApiFailure(503, "OVERLOADED", "Too many conversations are running.");
    }

    private long occupiedSlots() {
        return handles.size() + reservations.keySet().stream().filter(id -> !handles.containsKey(id)).count();
    }

    public record Reservation(SessionId session, UUID owner) {
    }

    public synchronized void wake(SessionId id, String assistantId) {
        wakeConfigured(id, null);
    }

    /** The orchestration host supplies a frozen, revocable configuration for an isolated worker. */
    public synchronized CompletableFuture<Void> wakeWorker(SessionId id, AgentConfigSnapshot config,
                                                           java.util.function.BooleanSupplier authorized,
                                                           List<ToolDefinition> additionalTools) {
        if (!authorized.getAsBoolean()) throw new IllegalStateException("worker mandate is not active");
        workerPolicies.put(id, new WorkerPolicy(config, authorized, List.copyOf(additionalTools)));
        wakeConfigured(id, config);
        return handles.get(id).agent().whenIdle();
    }

    private void wakeConfigured(SessionId id, AgentConfigSnapshot workerConfig) {
        if (closed)
            throw new ApiFailure(503, "SHUTTING_DOWN", "The application is shutting down.");
        AgentHandle handle = handles.get(id);
        if (handle == null) {
            ensureCapacity(id);
            SessionSnapshot snapshot = sessions.load(id);
            if (snapshot.session().hasOpenTurn())
                throw new ApiFailure(409, "RECOVERY_REQUIRED",
                        "The interrupted conversation requires recovery before continuing.");
            AgentConfigSnapshot config = workerConfig == null ? config(policies.resolve(id)) : workerConfig;
            Owned owned = new Owned(id, snapshot.session().header().cwd());
            environments.put(id, owned);
            try {
                handle = runtime.resume(id,
                        new AgentOptions(config, owned.environment.environment(), CancellationToken.none()));
            } catch (IllegalStateException error) {
                closeQuietly(environments.remove(id));
                throw new ApiFailure(409, "RECOVERY_REQUIRED", "The conversation cannot be resumed safely.");
            }
            handles.put(id, handle);
        }
        handle.agent().runMaintenance();
    }

    /**
     * Re-resolved by the loop at every turn start. A conversation override
     * therefore
     * applies to the next turn without changing the currently frozen turn.
     */
    private AgentConfigSnapshot resolveConfig(AgentContext context) {
        WorkerPolicy worker = workerPolicies.get(context.sessionId());
        if (worker != null) {
            if (!worker.authorized().getAsBoolean()) throw new IllegalStateException("worker mandate is not active");
            return worker.config();
        }
        return config(policies.resolve(context.sessionId()));
    }

    /**
     * Resolves conversation override > assistant > safe defaults into a frozen
     * snapshot.
     */
    private AgentConfigSnapshot config(ConversationPolicyService.Effective effective) {
        return ConversationConfig.freeze(effective, clock);
    }

    public synchronized CancelResult cancel(SessionId id) {
        return cancel(id, CancelOptions.user());
    }

    public synchronized CancelResult cancel(SessionId id, CancelOptions options) {
        AgentHandle handle = handles.get(id);
        return handle == null ? new CancelResult.NothingToCancel() : handle.agent().cancel(options);
    }

    /** Dispose an archived writer only after its actual execution has drained. */
    public void retire(SessionId id) {
        AgentHandle handle;
        synchronized (this) {
            handle = handles.get(id);
        }
        if (handle == null)
            return;
        try {
            handle.close();
        } catch (Exception error) {
            // An unconfirmed drain must not free capacity or permit a second writer.
            throw new ApiFailure(409, "RECOVERY_REQUIRED", "The archived conversation could not be drained safely.");
        }
        try {
            pipeline.awaitQuiescence(id.value(), Duration.ofSeconds(30));
        } finally {
            closeQuietly(environments.remove(id));
            sessionTools.remove(id);
            workerPolicies.remove(id);
        }
        synchronized (this) {
            handles.remove(id, handle);
        }
    }

    private void evictIdle() {
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            AgentHandle handle = entry.getValue();
            if (!reservations.containsKey(entry.getKey()) && handle.agent().status() == AgentStatus.IDLE
                    && handle.agent().whenIdle().isDone()) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                    /* No running turn; resume still checks durable state. */}
                closeQuietly(environments.remove(entry.getKey()));
                sessionTools.remove(entry.getKey());
                workerPolicies.remove(entry.getKey());
                iterator.remove();
                return;
            }
        }
    }

    /** One isolated local execution environment per session writer. */
    private final class Owned implements AutoCloseable {
        final LocalExecutionEnvironment environment;

        Owned(SessionId id, Path workspace) {
            Path spill = paths.dataDirectory().resolve("tool_outputs");
            this.environment = new LocalExecutionEnvironment(workspace, spill, RuntimeLimits.defaults());
            sessionTools.put(id, BuiltinTools.definitions(environment.environment(), skillCatalog, memory, search)
                    .stream().map(tool -> guardWorker(id, tool)).toList());
        }

        @Override
        public void close() {
            environment.close();
        }
    }

    private record WorkerPolicy(AgentConfigSnapshot config, java.util.function.BooleanSupplier authorized,
                                List<ToolDefinition> additionalTools) {}

    private ToolDefinition guardWorker(SessionId id, ToolDefinition tool) {
        return new ToolDefinition() {
            public ToolSchema schema() { return tool.schema(); }
            public ToolOutput output() { return tool.output(); }
            public Duration timeout() { return tool.timeout(); }
            public boolean concurrencySafe(tools.jackson.databind.JsonNode args) { return tool.concurrencySafe(args); }
            public boolean idempotent() { return tool.idempotent(); }
            public ToolResult execute(tools.jackson.databind.JsonNode args, ToolRunContext context) {
                context.signal().throwIfCancelled();
                WorkerPolicy policy = workerPolicies.get(id);
                if (policy != null && !policy.authorized().getAsBoolean()) return ToolResult.error("mandate_revoked_or_expired");
                return tool.execute(args, context);
            }
            public List<com.rikkahub.agent.message.ContentBlock> finalizeContent(ToolExecution execution, ToolResult result) {
                return tool.finalizeContent(execution, result);
            }
        };
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null)
            try {
                resource.close();
            } catch (Exception ignored) {
                /* shutdown path */}
    }

    @PreDestroy
    @Override
    public void close() {
        List<AgentHandle> snapshot;
        synchronized (this) {
            if (closed)
                return;
            closed = true;
            snapshot = List.copyOf(handles.values());
        }
        // Signal every writer before waiting for any one of them to drain.
        for (AgentHandle handle : snapshot) {
            try {
                handle.agent().cancel(new CancelOptions(true, CancelCause.SHUTDOWN, "shutdown"));
            } catch (IllegalStateException ignored) {
                /* A failed writer still drains through runtime.close(). */ }
        }
        try {
            runtime.close();
            for (SessionId id : new ArrayList<>(environments.keySet())) {
                pipeline.awaitQuiescence(id.value(), Duration.ofSeconds(30));
                closeQuietly(environments.remove(id));
                sessionTools.remove(id);
            }
            workers.shutdown();
            try {
                if (!workers.awaitTermination(30, TimeUnit.SECONDS))
                    workers.shutdownNow();
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } finally {
            synchronized (this) {
                handles.clear();
                reservations.clear();
            }
        }
    }
}
