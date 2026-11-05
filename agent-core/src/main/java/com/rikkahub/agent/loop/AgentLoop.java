package com.rikkahub.agent.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.rikkahub.agent.api.AgentContext;
import com.rikkahub.agent.api.CancelCause;
import com.rikkahub.agent.api.CancelledException;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.api.PreStepContext;
import com.rikkahub.agent.api.TurnStopContext;
import com.rikkahub.agent.event.AgentEvent;
import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.message.FinishReason;
import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.message.ToolCallBlock;
import com.rikkahub.agent.model.LlmFailure;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.port.ClaimedInput;
import com.rikkahub.agent.session.InboxConsumption;
import com.rikkahub.agent.session.NewSessionEvent;
import com.rikkahub.agent.session.Session;
import com.rikkahub.agent.session.SessionCommit;
import com.rikkahub.agent.session.SessionCommitResult;
import com.rikkahub.agent.session.SessionSnapshot;
import com.rikkahub.agent.session.SessionStatus;
import com.rikkahub.agent.session.SessionStatusTransition;
import com.rikkahub.agent.session.SurfaceMeta;
import com.rikkahub.agent.session.SurfaceOp;
import com.rikkahub.agent.session.UsageRecord;
import com.rikkahub.agent.session.event.AssistantAttemptEvent;
import com.rikkahub.agent.session.event.AssistantMessageEvent;
import com.rikkahub.agent.session.event.RequestContextEvent;
import com.rikkahub.agent.session.event.RequestHeaderEvent;
import com.rikkahub.agent.session.event.SessionEventPayload;
import com.rikkahub.agent.session.event.StepEnd;
import com.rikkahub.agent.session.event.StepStart;
import com.rikkahub.agent.session.event.StreamEvidence;
import com.rikkahub.agent.session.event.ToolCallEvent;
import com.rikkahub.agent.session.event.ToolResultEvent;
import com.rikkahub.agent.session.event.TurnEnd;
import com.rikkahub.agent.session.event.TurnEndReason;
import com.rikkahub.agent.session.event.TurnStart;
import com.rikkahub.agent.session.event.UserMessageEvent;
import com.rikkahub.agent.tool.ToolCall;
import com.rikkahub.agent.tool.ToolExecutionScope;
import com.rikkahub.agent.tool.ToolResult;
import com.rikkahub.agent.tool.ToolRunContext;
import com.rikkahub.agent.tool.ToolSchema;
import com.rikkahub.agent.tool.ToolScope;

/**
 * A single writer for one session. Every durable notification follows commit.
 */
public final class AgentLoop {
    // CLAIM_LEASE：Agent不是简单的从Inbox获取一个任务，需要先认领这个任务，认领之后这个任务在一定时间内归当前Agent处理
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    // AgentContext：保存Agent的运行上下文，如当前SessionID、当前AgentID、工作环境、模型上下文配置等等
    private final AgentContext context;
    private com.rikkahub.agent.config.AgentConfigSnapshot frozenConfig;
    // Agent 运行过程中需要的各种服务集中提供给 AgentLoop，具体在RuntimeServices.java
    private final RuntimeServices services;
    // 取消信号，如用户取消任务、Agent超时、父任务取消等等
    private final CancellationToken signal;
    // 以下是控制并发的变量
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object turnBoundary = new Object();
    private volatile boolean stopRequested;
    private boolean turnOpen;
    private volatile int turn;

    public AgentLoop(AgentContext context, RuntimeServices services, CancellationToken signal) {
        this.context = Objects.requireNonNull(context, "context");
        this.frozenConfig = context.config();
        this.services = Objects.requireNonNull(services, "services");
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    public boolean isRunning() {
        return running.get();
    }

    int currentTurn() {
        return turn;
    }

    CancellationToken cancellationForTurn(int expectedTurn) {
        synchronized (turnBoundary) {
            if (!turnOpen || turn != expectedTurn) {
                return null;
            }
            // Reserve the stop before releasing the boundary. Even if the
            // terminal commit wins the race, this loop cannot start its successor.
            stopRequested = true;
            return signal;
        }
    }

    // 管理turn的生命周期
    public void runTurnLoop() {
        // 使用compareAndSet操作保证只能有一个线程运行当前循环：如果 running 当前为 false，就将它设置为 true；否则操作失败
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("loop is already running");
        }
        // 定时任务线程池，负责Turn的超时控制
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-turn-timeout");
            t.setDaemon(true);// 守护线程，不会阻止 JVM 正常退出
            return t;
        });
        try {
            // 没有收到取消信号、没有请求停止、Inbox中存在可以唤醒Agent的输入
            while (!signal.isCancelled() && !stopRequested && hasWakeableInput()) {
                // 从Inbox中获取输入
                /*
                 * 优先获取 NEXT_TURN，如果没有，再尝试获取 NEXT_STEP。
                 * 如果两种都没有，就退出循环。
                 */
                ClaimedInput input = claim(InboxTarget.NEXT_TURN);
                if (input == null) {
                    input = claim(InboxTarget.NEXT_STEP);
                }
                if (input == null) {
                    break;
                }
                // Agent 开始新一轮任务之前，读取当前 Session 状态
                // 如果发现 Session 里面还有没有结束的 Turn，就直接抛出异常
                SessionSnapshot snapshot = snapshot();
                if (snapshot.session().hasOpenTurn()) {
                    throw new IllegalStateException("open turn requires recovery before resume");
                }
                synchronized (turnBoundary) {
                    if (!stopRequested && !signal.isCancelled()) {
                        turn = new TurnDriver().nextTurn(snapshot.session());// 计算下一个turn编号
                        turnOpen = true;
                    }
                }
                if (!turnOpen || stopRequested || signal.isCancelled()) {
                    services.inbox().release(input.token(), true);
                    break;
                }
                freezeConfiguration(snapshot);
                consume(input, true, snapshot);// 把输入真正提交到 Session
                emit(new AgentEvent.TurnStarted(sessionId(), turn));// 通知外部事件监听者
                // 使用定时线程执行超时检查
                int deadlineTurn = turn;
                ScheduledFuture<?> deadline = timer.schedule(() -> {
                    CancellationToken token = cancellationForTurn(deadlineTurn);
                    if (token != null) {
                        token.cancel(CancelCause.TIMEOUT);
                    }
                }, timeoutNanos(), TimeUnit.NANOSECONDS);
                // 正式进入 Step 执行循环
                TurnEndReason reason;
                try {
                    reason = runSteps(input.item());
                } catch (InfrastructureFailure ex) {// 基础设施错误，直接向上抛出
                    throw ex;
                } catch (CancelledException ex) {// 取消原因
                    reason = cancelReason();
                } catch (RuntimeException ex) {// agent运行时错误：Turn 执行错误
                    reason = new TurnEndReason.Errored("turn_error", message(ex));
                } finally {
                    deadline.cancel(false);
                }
                try {
                    services.turnStoppingHook().onTurnStopping(
                            new TurnStopContext(sessionId(), turn, session().deriveMessages()));
                } catch (InfrastructureFailure ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    reason = new TurnEndReason.Errored("turn_stop_hook", message(ex));
                }
                if (signal.isCancelled()) {
                    reason = cancelReason();
                }
                commit(List.of(log(new TurnEnd(turn, reason))), List.of(), null,
                        new SessionStatusTransition(SessionStatus.RUNNING, SessionStatus.IDLE));
                // 关闭 Turn，并通知外部监听者
                synchronized (turnBoundary) {
                    turnOpen = false;
                }
                emit(new AgentEvent.TurnEnded(sessionId(), turn, reason));
            }
        } finally {
            timer.shutdownNow();
            running.set(false);
        }
    }

    // 在开始执行一轮之前，把当前这一轮用的配置确定下来，写入frozenConfig字段供后续全程使用
    private void freezeConfiguration(SessionSnapshot snapshot) {
        var config = Objects.requireNonNull(services.configResolver().apply(context));
        com.rikkahub.agent.approval.ApprovalPolicy approval = config.approval();
        for (var event : snapshot.session().events())
            if (event.data() instanceof com.rikkahub.agent.session.event.ApprovalPolicyEvent policy)
                approval = policy.policy();
        frozenConfig = com.rikkahub.agent.config.AgentConfigSnapshot.freeze(config.version(), config.model(),
                config.generation(), config.context(), approval,
                config.tools(), config.skills(), config.promptProfile(), config.turnTimeout(), config.maxSteps(),
                services.clock());
    }

    // 把外层（session 级）的context包装成一个单轮（turn）级别的AgentContext，返回给执行器使用
    private AgentContext turnContext() {
        return new AgentContext() {
            public com.rikkahub.agent.api.AgentId agentId() {
                return context.agentId();
            }

            public com.rikkahub.agent.session.SessionId sessionId() {
                return context.sessionId();
            }

            public com.rikkahub.agent.config.AgentConfigSnapshot config() {
                return frozenConfig;
            }

            public com.rikkahub.agent.execution.ExecutionEnvironment environment() {
                return context.environment();
            }

            public com.rikkahub.agent.scope.AgentScope scope() {
                return context.scope();
            }

            public CancellationToken cancellation() {
                return signal;
            }
        };
    }

    // 管理step的生命周期
    /*
     * AgentLoop：负责管理整个Agent的执行流程
     * StepDriver：负责具体构建LLM请求、收集模型响应
     */
    private TurnEndReason runSteps(InboxItem initial) {
        List<InboxItem> inputs = new ArrayList<>();
        inputs.add(initial);
        StepDriver driver = new StepDriver(turnContext(), services.llm(), services.tools(), services.events(),
                services.clock(), services.ids(), signal);
        // 创建工具执行作用域
        ToolScope toolScope = new ToolScope(sessionId(), context.agentId().value(),
                context.environment().workspace().root(), Set.of());
        // 从第一步到maxSteps的step循环
        for (int step = 1; step <= frozenConfig.maxSteps(); step++) {
            signal.throwIfCancelled();
            ClaimedInput extra;
            /*
             * 如果当前Turn执行过程中，又有新的输入进入Inbox
             * 并且目标是NEXT_STEP，就会在下一次模型调用之前将它们收集起来
             */
            while ((extra = claim(InboxTarget.NEXT_STEP)) != null) {
                consume(extra, false, snapshot());// false：不再创建一个新的turn
                inputs.add(extra.item());
            }
            // 创建一个PreStepContext，包含当前收集到的输入、从session派生出来的消息历史
            PreStepContext work = new PreStepContext(inputs, session().deriveMessages());
            boolean accepted = invokeHook(0, work);
            if (work.rejectionReason() != null) {// Hook明确拒绝执行
                return new TurnEndReason.Blocked(work.rejectionReason());
            }
            if (!accepted) {// Hook链没有正常继续
                return new TurnEndReason.Blocked("pre_step_stopped");
            }
            // 自动压缩发生在 step 边界；摘要调用不持有数据库连接，事件由唯一写者提交。
            if (services.compaction() != null) {
                com.rikkahub.agent.context.CompactionExecution compaction;
                try {
                    compaction = services.compaction().compact(turnContext(), work.messages(), turn, step);
                } catch (InfrastructureFailure ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw new InfrastructureFailure(ex);
                }
                if (compaction != null) {
                    List<NewSessionEvent> compactionEvents = new ArrayList<>();
                    compactionEvents.add(log(compaction.start()));
                    if (compaction.summary() != null) {
                        compactionEvents.add(log(compaction.summary()));
                    }
                    if (compaction.replacement() != null) {
                        compactionEvents.add(new NewSessionEvent(compaction.replacement(),
                                compaction.surface(), false));
                    }
                    compactionEvents.add(log(compaction.end()));
                    commit(compactionEvents, List.of(), null, null);
                    work.rewrite(session().deriveMessages());
                }
            }
            if (work.messages().isEmpty()) {// 最终上下文没有消息，无法调用模型。
                return new TurnEndReason.Blocked("empty_context");
            }
            signal.throwIfCancelled();
            // 构建LLM请求
            LlmRequest request = driver.buildRequest(work.messages(), toolScope, turn, step);
            commit(List.of(
                    log(new StepStart(turn, step)),
                    log(new RequestHeaderEvent(driver.serialize(request))),
                    log(new RequestContextEvent(request.model().provider(), request.model().model(),
                            frozenConfig.context().contextWindowTokens(),
                            frozenConfig.context().systemPromptUpdate().name(), "turn"))),
                    List.of(), null, null);
            emit(new AgentEvent.StepStarted(sessionId(), turn, step));
            // 调用模型并收集响应
            StepDriver.CollectedStream response = collect(driver, request, step);
            if (response.cancelled() || signal.isCancelled()) {
                if (response.failure() == null) {
                    // 模型请求过程中发生取消：如果模型已经生成了一部分文本，就保留这部分文本
                    List<ContentBlock> partial = response.text().isEmpty()
                            ? List.of()
                            : List.of(new TextBlock(response.text()));
                    // 把已经生成的内容保存到 Session
                    commit(List.of(surface(new AssistantMessageEvent(partial, response.usage(),
                            request.model().provider(), request.model().model(), "CANCELLED", evidence(response)))),
                            usage(request, response.usage()), null, null);
                }
                commit(List.of(log(new StepEnd(turn, step))), List.of(), null, null);
                return cancelReason();
            }
            if (response.failure() != null) {
                commit(List.of(log(new StepEnd(turn, step))), List.of(), null, null);
                return new TurnEndReason.Errored(response.failure().code(), response.failure().message());
            }
            // 处理agent中的工具调用
            Set<String> callIds = new HashSet<>();
            /*
             * 每个工具调用都需要一个唯一的ID
             * 后续工具结果需要通过这个ID与对应调用关联
             */
            for (StepDriver.AssembledToolCall call : response.toolCalls()) {
                if (call.callId().isBlank() || !callIds.add(call.callId())) {
                    commit(List.of(log(new StepEnd(turn, step))), List.of(), null, null);
                    return new TurnEndReason.Errored("invalid_tool_calls",
                            "tool call IDs must be nonblank and unique");
                }
            }
            // 构建模型消息
            // 普通文本对应TextBlock，工具调用对应ToolCallBlock
            List<ContentBlock> content = new ArrayList<>();
            if (!response.text().isEmpty()) {
                content.add(new TextBlock(response.text()));
            }
            for (StepDriver.AssembledToolCall call : response.toolCalls()) {
                content.add(new ToolCallBlock(call.callId(), call.toolName(), call.argumentsJson()));
            }
            // 保存相应的信息
            commit(List.of(surface(new AssistantMessageEvent(content, response.usage(),
                    request.model().provider(), request.model().model(), response.finishReason().name(),
                    evidence(response)))), usage(request, response.usage()), null, null);

            // 现在执行工具
            // 首先获取本次请求允许使用的工具
            Set<String> advertised = new HashSet<>();
            for (ToolSchema schema : request.tools()) {
                /*
                 * 模型即使生成了工具调用，也不能直接执行任何工具
                 * 只有本次请求中实际提供给模型的工具，才允许执行
                 */
                advertised.add(schema.name());
            }
            List<ToolCall> calls = new ArrayList<>();
            List<ToolCall> executable = new ArrayList<>();
            // 遍历工具调用
            for (StepDriver.AssembledToolCall assembled : response.toolCalls()) {
                // 构建ToolCall
                ToolCall call = new ToolCall(assembled.callId(), assembled.toolName(), assembled.argumentsJson());
                calls.add(call);
                // 记录工具调用
                commit(List
                        .of(log(new ToolCallEvent(turn, step, call.callId(), call.toolName(), call.argumentsJson()))),
                        List.of(), null, null);
                // emit通知外部工具开始执行
                emit(new AgentEvent.ToolCallStarted(sessionId(), call));
                if (advertised.contains(call.toolName()))
                    executable.add(call);
            }
            var checkpoint = new com.rikkahub.agent.approval.ApprovalCheckpoint() {
                public void asked(com.rikkahub.agent.approval.ApprovalRequest request) {
                    commit(List.of(log(new com.rikkahub.agent.session.event.ApprovalAskedEvent(request.id(),
                            request.callId(), request.toolName(), request.reason(), turn, request.argumentsDigest(),
                            request.expiresAt()))), List.of(), null, null);
                    emit(new AgentEvent.ApprovalRequested(sessionId(), request));
                }

                public void decided(com.rikkahub.agent.approval.ApprovalRequest request,
                        com.rikkahub.agent.approval.ApprovalOutcome outcome) {
                    String decision = outcome instanceof com.rikkahub.agent.approval.ApprovalOutcome.AllowedOnce
                            ? "allowed-once"
                            : outcome instanceof com.rikkahub.agent.approval.ApprovalOutcome.Rejected ? "rejected"
                                    : outcome instanceof com.rikkahub.agent.approval.ApprovalOutcome.Cancelled
                                            ? "cancelled"
                                            : "unavailable";
                    commit(List.of(log(new com.rikkahub.agent.session.event.ApprovalDecidedEvent(request.id(), decision,
                            decision))), List.of(), null, null);
                    emit(new AgentEvent.ApprovalResolved(sessionId(), request.id(), outcome));
                }
            };
            ToolRunContext runContext = new ToolRunContext(sessionId(), context.agentId().value(), toolScope.cwd(),
                    signal,
                    Map.of("approvalPolicy", frozenConfig.approval(), "toolPolicy", frozenConfig.tools(), "turn", turn,
                            "approvalCheckpoint", checkpoint, "outputLimiter", services.outputLimiter()));
            List<ToolResult> outcomes;
            try {
                outcomes = services.toolPipeline().executeBatch(executable,
                        new ToolExecutionScope(toolScope, runContext), signal);
            } catch (com.rikkahub.agent.approval.ApprovalPersistenceFailure error) {
                throw new InfrastructureFailure(error);
            }
            if (outcomes.size() != executable.size())
                throw new InfrastructureFailure(new IllegalStateException("tool result count mismatch"));
            int resultIndex = 0;
            for (ToolCall call : calls) {
                ToolResult result = advertised.contains(call.toolName())
                        ? Objects.requireNonNull(outcomes.get(resultIndex++))
                        : ToolResult.error("tool_disabled: tool was not advertised");
                commit(List.of(surface(
                        new ToolResultEvent(call.callId(), call.toolName(), result.content(), result.isError(),
                                result.metadata()))),
                        List.of(), null, null);
                emit(new AgentEvent.ToolResultReady(sessionId(), call.callId(), result));
            }
            commit(List.of(log(new StepEnd(turn, step))), List.of(), null, null);
            drainTools();
            //检查模型结束原因
            if (signal.isCancelled()) {
                return cancelReason();
            }
            if (response.finishReason() == FinishReason.LENGTH) {
                return new TurnEndReason.MaxTokens();
            }
            if (response.finishReason() == FinishReason.CONTENT_FILTER) {
                return new TurnEndReason.Blocked("content_filter");
            }
            //判断是否正常结束：没有工具调用、没有新的 Step 输入
            if (response.toolCalls().isEmpty() && !hasStepInput()) {
                return new TurnEndReason.Completed();
            }
            inputs = new ArrayList<>();//清空当前Step的输入，进入下一次循环
        }
        return new TurnEndReason.Blocked("max_steps");//超过最大Step数直接停止
    }

    //模型请求与重试机制
    private StepDriver.CollectedStream collect(StepDriver driver, LlmRequest request, int step) {
        for (int attempt = 1;; attempt++) {
            StepDriver.CollectedStream response = driver.collect(request, turn, step);
            LlmFailure failure = response.failure();
            if (failure == null && !response.cancelled() && response.finishReason() == FinishReason.ERROR) {
                failure = new LlmFailure("model_error", "model finished with ERROR", false, null, Map.of());
            }
            if (failure == null) {
                return response;
            }
            commit(List.of(log(new AssistantAttemptEvent(attempt, failure.code(), failure.message(),
                    failure.retryable(), response.usage()))), usage(request, response.usage()), null, null);
            if (signal.isCancelled() || !services.retryPolicy().shouldRetry(failure, attempt)) {
                return new StepDriver.CollectedStream(response.text(), response.reasoning(), response.usage(),
                        response.finishReason(), failure, response.cancelled(), response.toolCalls());
            }
            CountDownLatch cancelled = new CountDownLatch(1);
            try (AutoCloseable registration = signal.onCancel(cancelled::countDown)) {
                cancelled.await(services.retryPolicy().delayFor(attempt).toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                signal.cancel(CancelCause.PARENT);
            } catch (Exception ex) {
                throw new IllegalStateException("retry cancellation registration failed", ex);
            }
            signal.throwIfCancelled();
        }
    }

    private boolean invokeHook(int index, PreStepContext work) {
        if (work.rejectionReason() != null) {
            return false;
        }
        if (index == services.preStepHooks().size()) {
            return true;
        }
        AtomicBoolean called = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicBoolean handling = new AtomicBoolean(true);
        Thread owner = Thread.currentThread();
        long revision = work.revision();
        try {
            services.preStepHooks().get(index).handle(work, () -> {
                if (Thread.currentThread() != owner || !handling.get()) {
                    throw new IllegalStateException("pre-step chain must continue synchronously");
                }
                if (!called.compareAndSet(false, true)) {
                    throw new IllegalStateException("pre-step chain may only continue once");
                }
                accepted.set(invokeHook(index + 1, work));
            });
        } finally {
            handling.set(false);
        }
        return called.get() ? accepted.get() : work.revision() != revision;
    }

    private void consume(ClaimedInput input, boolean start, SessionSnapshot snapshot) {
        List<NewSessionEvent> events = new ArrayList<>();
        if (start) {
            events.add(log(new TurnStart(turn)));
        }
        events.add(surface(new UserMessageEvent(input.id().value(), input.item().content(), "user")));
        try {
            commit(events, List.of(), new InboxConsumption(input.token(), input.id()),
                    start ? new SessionStatusTransition(snapshot.status(), SessionStatus.RUNNING) : null);
        } catch (RuntimeException ex) {
            try {
                services.inbox().release(input.token(), true);
            } catch (RuntimeException release) {
                ex.addSuppressed(release);
            }
            throw ex;
        }
    }

    private void commit(List<NewSessionEvent> events, List<UsageRecord> usage, InboxConsumption consumed,
            SessionStatusTransition status) {
        SessionCommitResult result;
        try {
            result = services.sessions().commit(new SessionCommit(context.sessionId(),
                    session().lastSeq(), null, events, List.of(), usage, List.of(), consumed, status));
        } catch (InfrastructureFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // A rejected write must not be followed by a fabricated successful
            // turn closure, especially after a tool side effect already ran.
            throw new InfrastructureFailure(ex);
        }
        emit(new AgentEvent.SessionCommitted(sessionId(), result.committedSeq()));
    }

    void drainTools() {
        boolean interrupted = false;
        try {
            while (!services.toolPipeline().awaitQuiescence(sessionId(), Duration.ofMillis(100))) {
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
        } catch (RuntimeException ex) {
            throw new InfrastructureFailure(ex);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ClaimedInput claim(InboxTarget target) {
        try {
            return services.inbox().claim(context.sessionId(), target, context.agentId().value(), CLAIM_LEASE);
        } catch (RuntimeException ex) {
            throw new InfrastructureFailure(ex);
        }
    }

    private boolean hasStepInput() {
        return pending().stream()
                .anyMatch(i -> i.target() == InboxTarget.NEXT_STEP);
    }

    boolean hasWakeableInput() {
        return pending().stream()
                .anyMatch(i -> !Boolean.FALSE.equals(i.item().metadata().get("wake")));
    }

    private Session session() {
        return snapshot().session();
    }

    private SessionSnapshot snapshot() {
        try {
            return services.sessions().load(context.sessionId());
        } catch (RuntimeException ex) {
            throw new InfrastructureFailure(ex);
        }
    }

    private List<com.rikkahub.agent.port.PendingInput> pending() {
        try {
            return services.inbox().listPending(context.sessionId());
        } catch (RuntimeException ex) {
            throw new InfrastructureFailure(ex);
        }
    }

    private static final class InfrastructureFailure extends RuntimeException {
        private InfrastructureFailure(Throwable cause) {
            super("agent infrastructure operation failed", cause);
        }
    }

    private String sessionId() {
        return context.sessionId().value();
    }

    private TurnEndReason cancelReason() {
        return signal.cause() == CancelCause.SHUTDOWN
                ? new TurnEndReason.Shutdown()
                : new TurnEndReason.Aborted(signal.cause() == null
                        ? "cancelled"
                        : signal.cause().name().toLowerCase(Locale.ROOT));
    }

    private long timeoutNanos() {
        try {
            return frozenConfig.turnTimeout().toNanos();
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private List<UsageRecord> usage(LlmRequest request, TokenUsage usage) {
        return List.of(new UsageRecord(context.sessionId(), request.model().provider(), request.model().model(),
                "turn", usage, services.clock().now()));
    }

    private static NewSessionEvent log(SessionEventPayload data) {
        return new NewSessionEvent(data, null, false);
    }

    private static NewSessionEvent surface(SessionEventPayload data) {
        return new NewSessionEvent(data, new SurfaceMeta(new SurfaceOp.SurfaceAppend(), List.of()), false);
    }

    private static List<StreamEvidence> evidence(StepDriver.CollectedStream response) {
        List<StreamEvidence> result = new ArrayList<>();
        if (!response.text().isEmpty()) {
            result.add(new StreamEvidence("text", response.text(), null, null, null));
        }
        if (!response.reasoning().isEmpty()) {
            result.add(new StreamEvidence("reasoning", response.reasoning(), null, null, null));
        }
        for (StepDriver.AssembledToolCall call : response.toolCalls()) {
            result.add(new StreamEvidence("tool_call", call.argumentsJson(), call.index(),
                    call.callId(), call.toolName()));
        }
        return result;
    }

    private void emit(AgentEvent event) {
        try {
            services.events().emit(event);
        } catch (RuntimeException ignored) {
            /* Observers cannot break the writer. */
        }
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
