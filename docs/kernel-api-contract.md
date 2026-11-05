# agent-core 内核 API 契约（P1）

> 状态：P1 功能闭环已完成（2026-11-02）
> 权威设计：[backend-architecture.md](backend-architecture.md) §4、§7
> 本文件固定 P1 的包、类型、方法签名与不变量。实现必须与本文一致；需要变更时先改本文与架构文档，再改代码。
>
> 当前落地范围：消息与事件、原子提交、inbox lease、模型深冻结、工具管线与 drain、运行循环和内存运行时。P2 的 JDBC/HTTP/SSE、P3 的本地工具/审批、P4 的上下文/技能/记忆/搜索/MCP，以及 P5 的编排领域与持久效果队列均已实现；编排的新增公共类型和运行语义见 [orchestration.md](orchestration.md)。阶段验收与临时测试清理记录见实施计划。

## 通用约定

- Java 17，`com.rikkahub.agent` 根包。
- 只允许 `jackson-databind`、`jackson-datatype-jsr310`、`slf4j-api` 作为主依赖。
- 不可变：record 的集合字段在构造时复制；模型请求的嵌套 JSON 选项/元数据递归冻结，工具 schema 的 `JsonNode` 在构造和读取时防御性复制。
- 校验：id 类 record 在紧凑构造器里拒绝 `null`/空白/非法字符，抛 `IllegalArgumentException`。
- 时间统一 `java.time.Instant`，依赖 `port.Clock` 注入，禁止在核心业务里直接 `Instant.now()`。
- 事件 JSON 使用显式 `type` 判别字段：`@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")` + `@JsonSubTypes`。不要依赖类名。

## 包依赖方向

```text
message ← 所有包
scope   ← 所有包（服务定位）
approval ← tool, loop
session  ← port, api, loop
model    ← api(AgentSpec), loop, plugins
tool     ← api(AgentSpec), loop, plugins
execution ← api, loop, plugins
event    ← api, loop
port     ← loop, backend（持久化输入 DTO 引用 api.InboxItem/InboxTarget）
config   ← api, loop
api      ← loop, backend
loop     ← backend
```

禁止：`agent-core` → Spring/JDBC/Servlet；任何包 → `com.rikkahub.features`/`platform`。

---

## `message`

```java
public sealed interface ContentBlock permits TextBlock, ImageBlock, ToolCallBlock, ToolResultBlock {}

public record TextBlock(String text) implements ContentBlock {}

public record ImageBlock(String mediaType, String base64Data) implements ContentBlock {}

public record ToolCallBlock(String callId, String toolName, String argumentsJson)
        implements ContentBlock {}

public record ToolResultBlock(String callId, String toolName,
                              List<ContentBlock> content, boolean error)
        implements ContentBlock {}

public enum MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

public enum MessageSource { SYSTEM, USER, MODEL, TOOL, COMPACTION }

public record Message(String id, MessageRole role, List<ContentBlock> content,
                      Instant createdAt, MessageSource source) {
    public static Message system(String id, String text, Instant at);
    public static Message user(String id, List<ContentBlock> content, Instant at);
    public static Message assistant(String id, List<ContentBlock> content, Instant at);
    public static Message tool(String id, List<ContentBlock> content, Instant at);
    public String text();               // 拼接所有 TextBlock，用 "\n" 分隔
}

public record TokenUsage(int inputTokens, int outputTokens,
                         Integer cacheReadTokens, Integer cacheWriteTokens) {
    public static TokenUsage zero();
    public int totalTokens();
}

public enum FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, ERROR, CANCELLED }

public sealed interface StreamChunk
        permits StreamChunk.TextDelta, StreamChunk.ReasoningDelta, StreamChunk.ToolCallDelta,
                StreamChunk.Usage, StreamChunk.Finished, StreamChunk.Failed, StreamChunk.Cancelled {
    record TextDelta(String text) implements StreamChunk {}
    record ReasoningDelta(String text) implements StreamChunk {}
    record ToolCallDelta(int index, String callId, String toolName, String argumentsFragment)
            implements StreamChunk {}
    record Usage(TokenUsage usage) implements StreamChunk {}
    record Finished(FinishReason reason) implements StreamChunk {}
    record Failed(String code, String message, boolean retryable) implements StreamChunk {}
    record Cancelled() implements StreamChunk {}
}
```

不变量：

- `ToolCallBlock.argumentsJson` 永远保存模型原始参数 JSON，不解析、不重排。
- `Message` 构造后内容不可变；`text()` 只读 `TextBlock`。
- `StreamChunk` 只走进程内事件，不落库，不加 JSON 注解。

---

## `session`

```java
public record SessionId(String value) {
    public SessionId { require text matches [A-Za-z0-9._:-]{1,128} }
    public static SessionId of(String value);
    @Override public String toString();
}

public record SessionHeader(
        SessionId id,
        SessionId parentSessionId,       // 可空
        Path cwd,
        int formatVersion,
        String origin,                   // 可空：chat / fork / worker / resume
        String agentPreset,              // 可空
        Map<String, String> metadata,    // 产品侧不透明标签，例如 conversationId
        Instant createdAt
) {
    public static SessionHeader newSession(SessionId id, Path cwd, Instant at);
}

public enum SessionStatus { CREATED, RUNNING, IDLE, INTERRUPTED, ENDED }

public record SurfaceMeta(SurfaceOp op, List<Long> sourceEventSeqs) {}

public sealed interface SurfaceOp permits SurfaceAppend, SurfaceReplace {
    record SurfaceAppend() implements SurfaceOp {}
    record SurfaceReplace(long startSeq, long endSeq) implements SurfaceOp {}
}

/** 未分配 seq/time 的事件；写入时由 SessionStore 在事务内分配。 */
public record NewSessionEvent(SessionEventPayload data, SurfaceMeta surface, boolean ignorable) {
    public NewSessionEvent {
        Objects.requireNonNull(data, "data");
    }
}

/** 唯一提交命令；创建、追加、fork 都走这里。 */
public record SessionCommit(
        SessionId sessionId,
        long expectedSeq,                 // 0 = 新会话或 fork
        SessionHeader header,             // 仅创建/fork 非空
        List<NewSessionEvent> events,     // 正常追加；store 分配 seq/time
        List<SessionEvent> fixedEvents,   // 仅 fork：保留原 seq 的前缀
        List<UsageRecord> usage,
        List<EffectIntent> effects,
        InboxConsumption inboxConsumption, // 可空
        SessionStatusTransition status     // 可空
) {
    // 紧凑构造器：events/fixedEvents 二选一；fixedEvents 必须从 1 连续；
    // 新会话或 fork 必须带 header 且 expectedSeq == 0；
    // parentSessionId 非空时必须有非空 fixedEvents。
}

public record SessionCommitResult(SessionId sessionId, long committedSeq, long lastSeq,
                                  long metadataVersion) {}

public record SessionStatusTransition(SessionStatus expected, SessionStatus next) {}

public record InboxConsumption(ClaimToken claimToken, InputId inputId) {}

public record InputId(String value) {
    public InputId { /* 非空、长度与字符集校验 */ }
}

public record ClaimToken(String value) {
    public ClaimToken { /* 非空校验；只由 InboxStore.claim 生成 */ }
}

public record UsageRecord(SessionId sessionId, String provider, String model, String purpose,
                          TokenUsage usage, Instant at) {}

public record EffectIntent(String id, String kind, String payloadJson, Instant availableAt) {}

public record SessionEvent(long seq, Instant time, SessionEventPayload data,
                           SurfaceMeta surface, boolean ignorable) {
    public static SessionEvent of(long seq, Instant time, SessionEventPayload data);
    public static SessionEvent surface(long seq, Instant time, SessionEventPayload data,
                                       SurfaceOp op, List<Long> sourceSeqs);
}
```

上述提交类型、`InputId`、`ClaimToken`、`UsageRecord` 均位于 `session`；`SessionCommit` 不依赖 `port`。`committedSeq` 和 `lastSeq` 在内存实现中均为提交后的日志尾序号；`metadataVersion` 与日志进度独立，当前提交命令不编辑产品元数据，内存实现返回 0。

### 事件 payload（全部带 `type` 判别）

```java
@JsonTypeInfo(...)
@JsonSubTypes({
  @Type(value = TurnStart.class, name = "turn/start"),
  @Type(value = TurnEnd.class, name = "turn/end"),
  @Type(value = StepStart.class, name = "step/start"),
  @Type(value = StepEnd.class, name = "step/end"),
  @Type(value = UserMessageEvent.class, name = "user/message"),
  @Type(value = SystemMessageEvent.class, name = "system/message"),
  @Type(value = AssistantMessageEvent.class, name = "assistant/message"),
  @Type(value = AssistantAttemptEvent.class, name = "assistant/attempt"),
  @Type(value = ToolCallEvent.class, name = "tool/call"),
  @Type(value = ToolResultEvent.class, name = "tool/result"),
  @Type(value = RequestHeaderEvent.class, name = "request/header"),
  @Type(value = RequestContextEvent.class, name = "request/context"),
  @Type(value = ApprovalPolicyEvent.class, name = "approval/policy"),
  @Type(value = ApprovalAskedEvent.class, name = "approval/asked"),
  @Type(value = ApprovalDecidedEvent.class, name = "approval/decided"),
  @Type(value = CompactionStartEvent.class, name = "compaction/start"),
  @Type(value = CompactionSummaryEvent.class, name = "compaction/summary"),
  @Type(value = CompactionEndEvent.class, name = "compaction/end"),
  @Type(value = BranchSwitchEvent.class, name = "branch/switch"),
  @Type(value = SessionEndSeedEvent.class, name = "session/end-seed")
})
public sealed interface SessionEventPayload permits ... {}

public record TurnStart(int turn) implements SessionEventPayload {}
public record TurnEnd(int turn, TurnEndReason reason) implements SessionEventPayload {}

public sealed interface TurnEndReason permits TurnEndReason.Completed, Aborted, Blocked,
        Errored, MaxTokens, Interrupted, Forked, Shutdown {
    record Completed() implements TurnEndReason {}
    record Aborted(String reason) implements TurnEndReason {}
    record Blocked(String reason) implements TurnEndReason {}
    record Errored(String code, String message) implements TurnEndReason {}
    record MaxTokens() implements TurnEndReason {}
    record Interrupted() implements TurnEndReason {}
    record Forked() implements TurnEndReason {}
    record Shutdown() implements TurnEndReason {}
}

public record StepStart(int turn, int step) implements SessionEventPayload {}
public record StepEnd(int turn, int step) implements SessionEventPayload {}

public record UserMessageEvent(String inputId, List<ContentBlock> content, String source)
        implements SessionEventPayload {}
public record SystemMessageEvent(String text) implements SessionEventPayload {}
public record AssistantMessageEvent(List<ContentBlock> content, TokenUsage usage,
        String provider, String model, String stopReason, List<StreamEvidence> streamEvidence)
        implements SessionEventPayload {}
public record AssistantAttemptEvent(int attempt, String failureCode, String failureMessage,
        boolean retryable, TokenUsage usage) implements SessionEventPayload {}
public record ToolCallEvent(int turn, int step, String callId, String toolName,
        String argumentsJson) implements SessionEventPayload {}
public record ToolResultEvent(String callId, String toolName, List<ContentBlock> content,
        boolean isError) implements SessionEventPayload {}
public record RequestHeaderEvent(String requestJson) implements SessionEventPayload {}
public record RequestContextEvent(String provider, String model, int contextWindow,
        String systemPromptUpdate, String purpose) implements SessionEventPayload {}
public record ApprovalPolicyEvent(ApprovalPolicy policy, int policyVersion)
        implements SessionEventPayload {}
public record ApprovalAskedEvent(String approvalId, String callId, String toolName,
        String reason) implements SessionEventPayload {}
public record ApprovalDecidedEvent(String approvalId, String decision, String note)
        implements SessionEventPayload {}
public record CompactionStartEvent(String compactionId, int trigger) implements SessionEventPayload {}
public record CompactionSummaryEvent(String compactionId, long shadowedStartSeq,
        long shadowedEndSeq, List<Long> shadowedSeqs, String summary, TokenUsage usage,
        String provider, String model) implements SessionEventPayload {}
public record CompactionEndEvent(String compactionId) implements SessionEventPayload {}
public record BranchSwitchEvent(String targetNodeId, long fromSeq, long toSeq,
        List<Long> shadowedSeqs) implements SessionEventPayload {}
public record SessionEndSeedEvent(boolean inherited, long inheritedEventCount)
        implements SessionEventPayload {}

public record StreamEvidence(String kind, String text, Integer index, String callId,
        String toolName) {}
```

`TurnEndReason` 不变量：循环只产生 `Completed/Aborted/Blocked/Errored/MaxTokens`；`Interrupted` 只能由恢复路径合成；`Forked` 只能由 fork seed 合成；`Shutdown` 只能由优雅停机路径合成，不得复用 `Interrupted`。

### Surface 与派生

```java
public final class Session {
    public Session(SessionHeader header, List<SessionEvent> events);  // 校验 seq 从 1 连续递增
    public SessionHeader header();
    public List<SessionEvent> events();
    public int formatVersion();
    public long lastSeq();               // 无事件为 0
    public long nextSeq();               // lastSeq + 1
    public Session append(SessionEvent event);          // 校验 event.seq == nextSeq
    public Session appendAll(List<SessionEvent> events);
    public List<Message> deriveMessages();
    public Optional<SessionEvent> lastEvent();
    public boolean hasOpenTurn();
}

public final class SessionFormat {
    public static final int CURRENT = 1;
}

public record SessionSnapshot(Session session, SessionStatus status, Instant updatedAt) {}
public record SessionSummary(SessionId sessionId, SessionStatus status, long lastSeq,
                             Instant updatedAt) {}
public interface EventUpcaster { SessionEventPayload upcast(int fromVersion, String type, JsonNode data); }
```

`deriveMessages()` 语义（必须逐条测试）：

1. 按 `seq` 升序处理事件。
2. `surface == null` 或 `ignorable == true` 的事件不进入消息视图（但仍保留在 `events()`）。
3. `SurfaceAppend`：把该事件转换为 `Message` 追加到 surface 列表。转换规则：
   - `SystemMessageEvent` → `SYSTEM`，内容 `TextBlock(text)`
   - `UserMessageEvent` → `USER`，内容原样
   - `AssistantMessageEvent` → `ASSISTANT`，内容原样
   - `ToolResultEvent` → `TOOL`，内容 `List.of(new ToolResultBlock(callId, toolName, content, isError))`
   - 其他 payload 带 surface 视为非法，构造/追加时抛 `IllegalArgumentException`
4. `SurfaceReplace(startSeq, endSeq)`：先移除 surface 中 `seq ∈ [startSeq, endSeq]` 的条目，再把当前事件按同样的转换规则插入到被移除区间的位置（若区间为空则追加）。
5. 消息 id 由事件 seq 派生：`"m-" + seq`；时间取事件 `time`。
6. `deriveMessages()` 不修改 `events()`，可重复调用且结果稳定。

---

## `model`

```java
public record ModelRef(String provider, String model) { /* 非空校验 */ }

public enum ModelCapability { TEXT, VISION, TOOLS, REASONING, STREAMING }

public record ProviderInfo(String id, String displayName, Set<ModelCapability> capabilities) {}

public record ModelInfo(String id, String displayName, int contextWindow,
                        int maxOutputTokens, boolean supportsTools, boolean supportsReasoning) {}

public record GenerationOptions(double temperature, double topP, int maxOutputTokens,
                                List<String> stopSequences, Map<String, Object> extra) {
    public static GenerationOptions defaults();
}

public record LlmRequest(String requestId, ModelRef model, String systemPrompt,
                         List<Message> messages, List<ToolSchema> tools,
                         GenerationOptions generation, Map<String, Object> metadata,
                         Instant createdAt) {
    // 紧凑构造器全量 copy；发出后不可变
}

public record LlmFailure(String code, String message, boolean retryable,
                         Integer statusCode, Map<String, Object> details) {}

public interface StreamListener {
    void onChunk(StreamChunk chunk);
    void onFailure(LlmFailure failure);
    void onComplete();
}

public interface LlmStream extends AutoCloseable {
    void subscribe(StreamListener listener);
    @Override default void close() {}
}

public interface LlmAdapter {
    ProviderInfo provider();
    List<ModelInfo> models();
    LlmStream stream(LlmRequest request, CancellationToken signal);
}

public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
                          Duration maxDelay) {
    public static RetryPolicy defaults();
    public Duration delayFor(int attempt);
    public boolean shouldRetry(LlmFailure failure, int attempt);
}
```

不变量：`LlmRequest` 一旦交给 adapter 不得再组装或修改；`GenerationOptions.extra`、请求 `metadata` 的嵌套集合/数组/JSON 节点被复制并冻结，循环引用和不支持的值被拒绝。`metadata.configSnapshot` 保存完整冻结配置，包括 `version/hash/frozenAt`，随 `request/header` 进入事件日志。重试复用同一请求，不重新执行 pre-step、不重新渲染 prompt。`Finished` 记录正常结束原因，继续收集后续 usage 直到 `onComplete`；失败、取消或超时终结当次收集，取消 adapter 专属令牌，迟到回调不改写结果。`subscribe` 须及时返回，阻塞工作由 adapter 自己管理。

---

## `tool`

```java
public record ToolSchema(String name, String description, JsonNode parameters) {
    // name 匹配 [a-zA-Z0-9_-]{1,64}
}

public enum ToolOutput { INLINE, SPILL }

public record ToolCall(String callId, String toolName, String argumentsJson) {}

public record ToolResult(List<ContentBlock> content, boolean isError,
                         Map<String, Object> metadata) {
    public static ToolResult text(String text);
    public static ToolResult error(String message);
}

public record ToolScope(String sessionId, String agentId, Path cwd,
                        Set<String> enabledTools) {}

public record ToolRunContext(String sessionId, String agentId, Path cwd,
                             CancellationToken signal, Map<String, Object> attributes) {}

public record ToolExecution(ToolCall call, Instant startedAt, Duration duration) {}

public interface ToolDefinition {
    ToolSchema schema();
    default ToolOutput output() { return ToolOutput.INLINE; }
    ToolResult execute(JsonNode arguments, ToolRunContext context);
    default Duration timeout() { return Duration.ofSeconds(120); }
    default boolean concurrencySafe(JsonNode arguments) { return false; }
    default boolean idempotent() { return false; }
    default List<ContentBlock> finalizeContent(ToolExecution execution, ToolResult result) {
        return result.content();
    }
}

public sealed interface ToolDecision permits ToolDecision.Allow, ToolDecision.Deny,
        ToolDecision.Ask, ToolDecision.Cancel {
    record Allow() implements ToolDecision {}
    record Deny(String reason) implements ToolDecision {}
    record Ask(String reason) implements ToolDecision {}
    record Cancel(String reason) implements ToolDecision {}
}

public record ToolExecutionScope(ToolScope tools, ToolRunContext context) {}

/** 扩展点默认继续；Allow 不覆盖已有拒绝，多个 Ask 合并审批。 */
public interface ToolHooks {
    default ToolDecision preExecute(ToolCall call, ToolRunContext context) {
        return new ToolDecision.Allow();
    }
    default ToolDecision guard(ToolCall call, ToolRunContext context) {
        return new ToolDecision.Allow();
    }
    default void postExecute(ToolCall call, ToolResult result, ToolRunContext context) {}
    default List<ContentBlock> projectContent(ToolCall call, ToolResult result) {
        return result.content();
    }
}

public interface ToolRegistry {
    List<ToolSchema> schemas(ToolScope scope);
    Optional<ToolDefinition> find(String name, ToolScope scope);
    AutoCloseable register(ToolDefinition tool, ToolScope scope);
}

/** 唯一执行入口；ToolRegistry 不再暴露第二个 execute。 */
public interface ToolPipeline {
    ToolResult execute(ToolCall call, ToolExecutionScope scope, CancellationToken signal);
    default boolean awaitQuiescence(Duration timeout) { return true; }
    default boolean awaitQuiescence(String sessionId, Duration timeout) {
        return awaitQuiescence(timeout);
    }
}

public final class DefaultToolPipeline implements ToolPipeline {
    public DefaultToolPipeline(ToolRegistry registry, ApprovalService approvals,
            ApprovalPolicy defaultPolicy, Clock clock, IdGenerator ids, ExecutorService executor);
    public DefaultToolPipeline(ToolRegistry registry, ApprovalService approvals,
            ApprovalPolicy defaultPolicy, Clock clock, IdGenerator ids,
            ExecutorService executor, ToolHooks hooks);
    public void addHooks(ToolHooks hooks);
}
```

管线顺序与语义（`DefaultToolPipeline` 实现）：

```text
lookup → enabled? → preExecute → guard → approval(ask) → execute → projectContent
       → postExecute → finalizeContent → ToolResult
```

- `Deny`/`Cancel`/未获审批 → `ToolResult.error(...)`，`isError=true`，不抛异常。
- 工具抛异常 → 结构化错误 `ToolResult.error(code + ": " + message)`。
- 超时 → `ToolResult.error("tool_timeout: ...")`，不把 `TimeoutException` 泄漏给模型。
- `Ask` 通过 `ApprovalService`；没有 broker 时返回 `Unavailable` → 拒绝（失败关闭）。
- `NEVER` 直接 `Rejected`，不允许 post 钩子绕过。
- 每个 `tool/call` 必须恰好产生一个 `tool/result`，成功/失败/拒绝/超时/取消都进入模型历史。
- `DefaultToolPipeline` 对同一 session 串行执行，实际旧任务未退出时返回 `tool_busy`；P3 已实现连续 `concurrencySafe` 并行组、独占屏障与输出限额/spill 引用。兼容的 pre/guard/post 注册方法仍保留，不能清除已有错误标记。
- 宿主注入并拥有 executor，管线不关闭它。取消和超时向工具专属令牌及执行线程发出停止请求；返回错误后仍追踪实际任务，`awaitQuiescence` 在任务真正退出后才返回 `true`。非协作式任务不能被强制停止，会阻止该 session 排空。
- `ToolScope.enabledTools` 的空集合表示全部启用；配置 `ToolPolicy.allowAll=false` 的空白名单表示不暴露工具。循环按冻结请求中实际公布的工具列表校验调用。

---

## `approval`

```java
public enum ApprovalPolicy { ASK, NEVER }

public sealed interface ApprovalOutcome
        permits ApprovalOutcome.AllowedOnce, ApprovalOutcome.Rejected,
                ApprovalOutcome.Cancelled, ApprovalOutcome.Unavailable {
    record AllowedOnce() implements ApprovalOutcome {}
    record Rejected(String reason) implements ApprovalOutcome {}
    record Cancelled(String reason) implements ApprovalOutcome {}
    record Unavailable(String reason) implements ApprovalOutcome {}
}

public record ApprovalRequest(String id, String sessionId, String callId,
                              String toolName, String argumentsJson, String reason,
                              Instant createdAt) {}

public interface ApprovalBroker {
    ApprovalOutcome request(ApprovalRequest request, CancellationToken signal);
}

public final class ApprovalService {
    public ApprovalService(ApprovalBroker broker);        // broker 可空
    public ApprovalOutcome request(ApprovalRequest request, ApprovalPolicy policy,
                                   CancellationToken signal);
}
```

- `NEVER` → `Rejected("policy")`，不调用 broker。
- `ASK` 且 broker 为空 → `Unavailable("no broker")`。
- 等待被取消 → `Cancelled("cancelled")`。

---

## `execution`

```java
public record Workspace(Path root) {
    public Path resolve(String path);   // normalize 后必须仍在 root 内，否则 IllegalArgumentException
}

public record ExecutionEnvironment(Workspace workspace, FileSystem fileSystem,
                                   ProcessRunner processRunner, Terminal terminal,
                                   JobManager jobManager) {}

public interface FileSystem {
    byte[] read(Path path);
    void write(Path path, byte[] content);
    boolean exists(Path path);
    List<Path> list(Path directory);
}

public record Command(List<String> argv, Path cwd, Map<String, String> environment,
                      Duration timeout) {}

public record ProcessResult(int exitCode, String stdout, String stderr, Duration duration) {}

public interface ProcessRunner {
    ProcessResult run(Command command, CancellationToken signal);
}

public record TerminalSpec(Path cwd, List<String> argv, Map<String, String> environment) {}
public interface TerminalHandle extends AutoCloseable {
    void write(String data);
    void resize(int columns, int rows);
    @Override void close();
}
public interface Terminal {
    TerminalHandle open(TerminalSpec spec, CancellationToken signal);
}

public record JobSpec(String name, Command command) {}
public record JobInfo(String id, String name, String status, Integer exitCode) {}
public interface JobManager {
    String submit(JobSpec job);
    List<JobInfo> list();
    boolean cancel(String jobId);
}

public enum SandboxPolicy { READ_ONLY, WORKSPACE_WRITE, FULL_ACCESS }
```

P1 只固定契约，不实现本地进程/PTY。

---

## `event`

```java
public sealed interface AgentEvent
        permits AgentEvent.TurnStarted, StepStarted, AssistantDelta, AssistantReasoning,
                ToolCallStarted, ToolResultReady, ApprovalRequested, ApprovalResolved,
                UsageUpdated, TurnEnded, SessionCommitted, Failure {
    record TurnStarted(String sessionId, int turn) implements AgentEvent {}
    record StepStarted(String sessionId, int turn, int step) implements AgentEvent {}
    record AssistantDelta(String sessionId, int turn, int step, int blockIndex, String text)
            implements AgentEvent {}
    record AssistantReasoning(String sessionId, int turn, int step, int blockIndex, String text)
            implements AgentEvent {}
    record ToolCallStarted(String sessionId, ToolCall call) implements AgentEvent {}
    record ToolResultReady(String sessionId, String callId, ToolResult result) implements AgentEvent {}
    record ApprovalRequested(String sessionId, ApprovalRequest request) implements AgentEvent {}
    record ApprovalResolved(String sessionId, String approvalId, ApprovalOutcome outcome)
            implements AgentEvent {}
    record UsageUpdated(String sessionId, int turn, int step, TokenUsage usage) implements AgentEvent {}
    record TurnEnded(String sessionId, int turn, TurnEndReason reason) implements AgentEvent {}
    record SessionCommitted(String sessionId, long seq) implements AgentEvent {}
    record Failure(String sessionId, String code, String message) implements AgentEvent {}
}

public interface AgentEventSink { void emit(AgentEvent event); }
public interface AgentEventListener { void onEvent(AgentEvent event); }
```

---

## `scope`

```java
public record ServiceKey<T>(String name) {
    public static <T> ServiceKey<T> of(String name);
}

public interface Disposable { void dispose(); }

public interface Registration extends AutoCloseable {
    @Override void close();
}

public final class AgentScope implements AutoCloseable {
    public <T> Registration provide(ServiceKey<T> key, T service);
    public <T> Optional<T> find(ServiceKey<T> key);
    public AgentScope child();
    public boolean isClosed();
    @Override public void close();       // 逆序 rollback 所有注册
}
```

不变量：父作用域关闭时子作用域自动关闭；重复 `close()` 幂等；注册顺序的逆序释放；`find` 先查自身再查父链。

---

## `port`

```java
public interface Clock { Instant now(); }

public interface IdGenerator {
    String newId(String prefix);
    default SessionId newSessionId() { return SessionId.of(newId("sess")); }
}

public interface SessionStore {
    SessionSnapshot load(SessionId id);
    /** 连续前缀，末序号不超过 afterSeq + limit；见下方读取语义。 */
    SessionSnapshot loadRange(SessionId id, long afterSeq, int limit);
    SessionCommitResult commit(SessionCommit commit);   // 唯一写入口
    List<SessionSummary> list();
}

public record PendingInput(InputId id, SessionId sessionId, InboxTarget target,
                           InboxItem item, Instant createdAt) {}

public record ClaimedInput(InputId id, SessionId sessionId, InboxTarget target,
                           InboxItem item, ClaimToken token, int attempts,
                           Instant claimedAt, Instant leaseUntil) {}

public interface InboxStore {
    void enqueue(PendingInput input);
    default ClaimedInput claim(SessionId id, String owner, Duration lease) {
        return claim(id, null, owner, lease);
    }
    ClaimedInput claim(SessionId id, InboxTarget target, String owner, Duration lease);
    /** 仅供提交事务内部调用；外部通过 SessionCommit.inboxConsumption 消费。 */
    void complete(ClaimToken token, InputId inputId);
    void release(ClaimToken token, boolean retryable);
    List<PendingInput> listPending(SessionId id);
}

public interface TelemetrySink { void event(String name, Map<String, Object> attributes); }
```

`SessionStore.commit` 是唯一写路径；创建与 fork 通过 `SessionCommit.header` / `fixedEvents` 表达，不再有 `create`/`append`/`flush`/`fork` 四个语义重叠的入口。`usage` 与 `effects` 随提交命令进入同一事务，因此不再有独立的 `UsageSink` bean。

`loadRange(id, afterSeq, limit)` 返回 seq 从 1 到 `min(lastSeq, afterSeq + limit)` 的连续前缀，而非仅返回 afterSeq 后的片段：`SessionSnapshot` 包含的 `Session` 要求日志从 seq 1 开始。`afterSeq >= 0`、`limit > 0`，加法溢出按最大序号处理。以后如需独立事件分页，应新增范围 DTO。

`InboxStore.claim` 在独立短事务中把 `PENDING` 原子地转为 `IN_PROGRESS`，写 lease/claim_token/attempts；`target == null` 接受任意 target，无可认领输入时返回 `null`。`complete` 仅由提交事务调用，把输入转为 `CONSUMED`。`release(token, true)` 重新排队，`false` 终止该输入；过期或失效 token 不能消费或释放新租约。

`loop.InMemoryAgentStore(Clock, IdGenerator)` 是可嵌入的进程内后端，同一个实例实现两个端口。它用单锁保证事件、状态 CAS、用量、效果和消费一起生效；`claim`、`listPending`、`release` 回收过期租约。独立调用 `complete` 会失败。`listUsage(SessionId)` / `listEffects(SessionId)` 提供已提交数据的只读快照；进程退出后数据丢失，JDBC 与崩溃恢复服务属于 P2。

创建必须带匹配 id 的 header；追加不能再次带 header。fork 必须有非空真实父前缀，保留原 seq/time，父会话为 IDLE 且没有打开的 turn，前缀末事件为 `TurnEnd`。消费必须同批写入匹配 inputId 和内容的用户消息，且 `surface` 为 `SurfaceAppend`、`ignorable=false`；NEXT_TURN 的 `turn/start` 在该消息之前，消费后的会话状态为 RUNNING。

---

## `config`

```java
public enum SystemPromptUpdatePolicy { REPLACE_IN_PLACE, APPEND }

public record ContextPolicy(int contextWindowTokens, int compactionThresholdTokens,
                            int keepRecentTokens, SystemPromptUpdatePolicy systemPromptUpdate) {
    public static ContextPolicy defaults(int contextWindowTokens);
}

public record ToolPolicy(Set<String> allowedTools, boolean allowAll,
                         ApprovalPolicy defaultApproval) {
    public static ToolPolicy allTools();
}

public record Limits(Duration turnTimeout, int maxSteps, Duration stepTimeout,
                     int maxToolOutputBytes) {
    public static Limits defaults();
}

public record AgentConfigSnapshot(
        long version,
        String hash,                   // freeze 时计算，跨进程稳定
        Instant frozenAt,
        ModelRef model,
        GenerationOptions generation,
        ContextPolicy context,
        ApprovalPolicy approval,
        ToolPolicy tools,
        List<String> skills,
        String promptProfile,
        Duration turnTimeout,
        int maxSteps
) {
    public static AgentConfigSnapshot freeze(long version, ModelRef model,
            GenerationOptions generation, ContextPolicy context, ApprovalPolicy approval,
            ToolPolicy tools, List<String> skills, String promptProfile,
            Duration turnTimeout, int maxSteps, Clock clock);
    public String fingerprint();       // 返回 hash
}
```

`freeze` 从注入的时钟读取一次 `frozenAt`。`hash` 是包含 version 和全部行为字段的稳定 SHA-256 指纹，不包含 frozenAt，不使用对象默认 `hashCode`；传入非空 hash 时必须匹配计算结果，传入 null 时计算。旧十参构造器 `(ModelRef, GenerationOptions, ContextPolicy, ApprovalPolicy, ToolPolicy, List<String>, String, Duration, int, long version)` 保留，冻结时间为 `Instant.EPOCH`；`fingerprint()` 返回 hash。skills 在 P4 前仍使用字符串标识。P1 的 `ToolPolicy` 支持 `allowedTools/allowAll/defaultApproval`；有序权限规则属于 P3。

---

## `api`

```java
public record AgentId(String value) { /* 非空校验 */ }

public enum AgentStatus { IDLE, RUNNING, DISPOSED }

public enum CancelCause { USER, TIMEOUT, SHUTDOWN, PARENT, ERROR, UNKNOWN }

public enum InboxTarget { NEXT_TURN, NEXT_STEP }

public record InboxItem(String id, List<ContentBlock> content,
                        Map<String, Object> metadata) {}

public record CancelOptions(boolean keepInbox, CancelCause cause, String reason,
                            Integer expectedTurn) {
    public CancelOptions(boolean keepInbox, CancelCause cause, String reason); // 兼容；expectedTurn=null
    public CancelOptions forTurn(int turn);   // 正整数；绑定一轮，过期请求不取消后继 turn
    public static CancelOptions user();       // keepInbox=true，expectedTurn=null
    public static CancelOptions shutdown();
}

public sealed interface CancelResult
        permits CancelResult.Cancelled, NothingToCancel, AlreadyCancelling {
    record Cancelled(long turn) implements CancelResult {}
    record NothingToCancel() implements CancelResult {}
    record AlreadyCancelling() implements CancelResult {}
}

public final class CancellationToken { ... }   // 见下

public interface AgentContext {
    AgentId agentId();
    SessionId sessionId();
    AgentConfigSnapshot config();
    ExecutionEnvironment environment();
    AgentScope scope();
    CancellationToken cancellation();
}

public interface Agent {
    AgentId id();
    SessionId sessionId();
    AgentStatus status();
    AgentContext context();
    void send(InboxItem item, InboxTarget target);
    void followup(InboxItem item);
    void steer(InboxItem item);
    void inject(InboxItem item);
    CancelResult cancel(CancelOptions options);
    CompletableFuture<Void> whenIdle();
    void runMaintenance();
}

public record AgentHandle(Agent agent, AutoCloseable disposer) implements AutoCloseable {
    @Override public void close() throws Exception;
}

public record AgentSpec(AgentId id, SessionHeader header, AgentConfigSnapshot config,
                        ExecutionEnvironment environment, List<InboxItem> initialInputs) {
    public AgentSpec(AgentId id, SessionHeader header, AgentConfigSnapshot config,
                     ExecutionEnvironment environment); // initialInputs 为空
}

public record AgentOptions(AgentConfigSnapshot config, ExecutionEnvironment environment,
                           CancellationToken parentSignal) {}

public interface AgentRuntime {
    AgentHandle create(AgentSpec spec);
    AgentHandle resume(SessionId sessionId, AgentOptions options);
    Optional<Agent> find(AgentId id);
    List<Agent> list();
}

public interface PreStepHook {
    void handle(PreStepContext context, Chain chain);
    interface Chain { void next(); }
}

public final class PreStepContext {
    // 可变的工作副本；hook 可以 accept/rewrite/reject
    public List<InboxItem> inputs();
    public List<Message> messages();
    public void rewrite(List<Message> messages);
    public void reject(String reason);
    public String rejectionReason();
}

public interface TurnStoppingHook { void onTurnStopping(TurnStopContext context); }

public final class TurnStopContext {
    public String sessionId();
    public int turn();
    public List<Message> messages();
}
```

说明：Hook 类型放在 `api` 而不是 `loop`，因为 `AgentSpec` 是稳定契约，`api` 不能依赖 `loop`。`loop` 依赖 `api` 实现 Hook 链。

`CancellationToken`：

```java
public final class CancellationToken {
    public static CancellationToken none();
    public boolean isCancelled();
    public CancelCause cause();                     // 未取消返回 null
    public AutoCloseable onCancel(Runnable listener); // 已取消则立即执行
    public void throwIfCancelled();                 // 抛 CancelledException
    public CancellationToken child();
    public boolean cancel(CancelCause cause);       // 首次取消返回 true
}
public class CancelledException extends RuntimeException { public CancelledException(CancelCause c); }
```

取消注册与触发并发时，每个监听器最多执行一次；取消后注册立即执行，关闭注册句柄可以移除监听器。子令牌取消会解除父注册；取消子令牌不取消父令牌。

---

## `loop`

```java
public record RuntimeServices(SessionStore sessions, InboxStore inbox, LlmAdapter llm,
                              ToolRegistry tools, ToolPipeline toolPipeline,
                              AgentEventSink events,
                              List<PreStepHook> preStepHooks, TurnStoppingHook turnStoppingHook,
                              Clock clock, IdGenerator ids, RetryPolicy retryPolicy) {}

public final class Inbox {
    public void enqueue(PendingInput input);
    public Optional<PendingInput> pollTurn();
    public Optional<PendingInput> pollStep();
    public boolean hasTurn();
    public boolean hasStep();
    public int size();
    public void clear();
}

public final class AgentLoop {
    public AgentLoop(AgentContext context, RuntimeServices services, CancellationToken signal);
    public void runTurnLoop();       // 处理可唤醒的输入，无 NEXT_TURN 时可用 NEXT_STEP 开新 turn
    public boolean isRunning();
}

public final class TurnDriver { ... }
public final class StepDriver { ... }
public final class InMemoryAgentStore implements SessionStore, InboxStore {
    public InMemoryAgentStore(Clock clock, IdGenerator ids);
    public List<UsageRecord> listUsage(SessionId id);
    public List<EffectIntent> listEffects(SessionId id);
}
public final class LoopAgent implements Agent, AutoCloseable { ... }
public final class DefaultAgentRuntime implements AgentRuntime, AutoCloseable {
    public DefaultAgentRuntime(RuntimeServices services);
}
```

`RuntimeServices` 是装配点：运行时的 LLM 适配器、工具注册表、事件出口、Hook、时钟与存储都从这里进入，`AgentSpec` 只描述“这一次 Agent 的身份、会话头、配置与执行环境”。这样 `resume()` 不需要再传适配器。

循环语义（P1 简化版，不含压缩与 steer 之后的复杂重排）：

```text
claim inbox(next-turn；无可用项时 next-step)
  → turn/start
  → user/message（携带 inputId）与 inbox.status=CONSUMED 同一提交
  → 组装 messages = session.deriveMessages()
  → pre-step hooks（accept / rewrite / reject）
  → step/start
  → 冻结 LlmRequest + request/header + request/context（log-only）
  → adapter.stream → 内存聚合 delta（ephemeral AgentEvent）
  → assistant/message（含 usage 与流证据）
  → tool/call → ToolPipeline.execute → tool/result（逐个；非 concurrencySafe 形成顺序屏障）
  → step/end
  → 有 tool/result 或 NEXT_STEP 输入 → 下一个 step
  → 无 → turn/end { completed }
```

不变量：

- `turn/start` 必须先于同轮 `user/message`。
- 认领使用独立 lease 短事务；随后 `turn/start`、可见 `user/message`、inbox 消费和会话状态使用同一个 `SessionCommit`。P2 将投影与数据库状态纳入同事务。
- 模型流失败：写 `assistant/attempt`，按 `RetryPolicy` 重试；重试不重新组装请求。
- 取消：不再开始新的 step；已开始的工具结果仍写入，并等待实际工具退出。用户/父取消及超时写 `aborted`，优雅关闭写 `shutdown`。取消时即使没有文本，已发生 usage 也必须提交；已记入失败尝试的用量不再重复记录。
- `LoopAgent` 串行调度唤醒；`DefaultAgentRuntime` 同时登记 AgentId 和 SessionId，拒绝同 runtime 内重复 live writer。`resume` 拒绝打开中的 turn 和 ENDED 会话，不自动重放可能计费或产生副作用的工作。
- `whenIdle()` 在没有可唤醒的待处理输入、运行中 turn 和工具任务时完成；完成回调可关闭 Agent。取消保留的输入可由后续 send 或 `runMaintenance()` 继续处理。
- 存储读取/提交或排空服务失败时，idle/关闭 future 必须异常终结，不能永久等待。工具执行后结果写入失败不合成正常或 errored 的 turn/end，保留打开的 turn 等待恢复，不重跑工具；未能确认工具排空时不释放该会话的写者。
- `initialInputs` 在 create 时入队；`inject()` 为 NEXT_STEP 输入加 `metadata.wake=false`，不唤醒循环，在下一 step 边界消费。普通 send 会移除该内部静默标记。
- runtime 关闭时停止接收、取消、等待工具 drain，再关闭 Agent 作用域；注入的宿主服务和工具 executor 的生命周期仍属于宿主。

---

## P1 实现状态（2026-11-02）

| 范围 | 已实现 |
| --- | --- |
| 事件与 surface | 20 类 payload、8 类 turn 结束原因、JSON 判别、append/replace、日志保留与消息派生 |
| 提交与输入 | 提交/claim 全部值类型；唯一 commit；同实例内存后端；序号与状态 CAS、usage/effects/消费原子；fork 边界与租约校验 |
| 工具 | 注册表原子快照；管线接口与默认实现；pre/guard/审批/project/post/finalize；超时、取消及实际任务 drain |
| 模型与配置 | 嵌套数据深冻结；稳定配置 hash、冻结时间与兼容构造器；重试复用请求；流终态与 usage 收集 |
| Agent 与 runtime | initialInputs；NEXT_TURN/NEXT_STEP；静默 inject；Hook 链；单写者；取消、唤醒、whenIdle、关闭及 resume 校验 |

P1 的内存功能闭环已完成。`BranchSwitchEvent` 记录分支控制证据，实际消息替换由配套消息事件的 `SurfaceReplace` 表达；产品分支切换与数据库投影属于 P2。`ApprovalRequest` 保留字符串 id 与原始 argumentsJson，审批投影及 expiry 等产品字段已在 P3 实现。

## P1 验收与测试保留

本轮使用临时 JUnit 案例验证以下行为：

1. ContentBlock、20 类 payload 与 8 类 turn 结束原因 JSON 往返，原始工具参数保留。
2. surface append/replace、ignorable/log-only、分支替换与原日志保留，拒绝非连续 seq。
3. 父子取消、注册/取消并发下监听最多执行一次、子令牌解除父注册；作用域查找与逆序关闭。
4. 创建/追加/fork、状态与 seq CAS、usage/effects/消费回滚、闭合 turn 边界、租约回收与失效 token、按 target 认领与并发独占。
5. 工具 hook 顺序、合并 Ask、NEVER、审批不可用、结构化异常、取消/超时、排队取消与非协作任务实际退出。
6. 冻结请求和配置、嵌套 JSON/数组、hash 与冻结时间；失败重试复用同一请求。
7. 文本、工具后再回复、流失败重试、冻结工具白名单、maxSteps、initialInputs 与静默 inject。
8. 取消后的用量不漏记、不重复；取消后新输入不丢唤醒；idle 回调关闭、异常创建清理、作用域关闭重入与工具 drain。

2026-11-02 的全模块 `mvn clean verify` 已通过：76 项临时内核测试、4 项原有架构检查全部成功。按用户要求，验收后删除本轮全部临时内核测试与案例，保留原 P0 架构测试和 fixture。没有交付永久 `agent-core-testkit`、`FakeSessionStore` 或 `ScriptedLlmAdapter` 工具包；生产内存实现是 `InMemoryAgentStore`。

```powershell
mvn clean verify
mvn -pl agent-core -am test
mvn -pl agent-core dependency:tree
```

本次 P1 重新验收使用 26 项行为测试与 2 项存储故障回归，修复上述异常收尾与工具结果提交失败路径后全部通过；P2 JDBC 持久化基座也已使用真实内核验证。临时测试按用户要求在最终验收后清理。

清理后的第一条命令验证全模块构建、依赖约束与保留的 P0 架构测试，并移除已删除测试残留的 class。第二条不会重新运行本轮内核案例，不能据其成功声称功能回归已复现；需要复现时须重新准备测试。JDBC 已落地，HTTP/SSE、真实 provider、恢复、本地执行环境、上下文/技能/记忆及编排仍在后续切片。
