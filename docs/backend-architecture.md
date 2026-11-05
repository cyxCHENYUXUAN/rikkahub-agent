# Java 后端骨架设计

> 状态：设计基线（2026-11-02 评审修订）
> 参考：DeepSeek Harness（developer preview；具体 tag/commit 见 `references/sources.json`）与 dsh-superagent-prototype（research prototype）。
> 原则：只借鉴架构不变量与领域模型，不复制上游代码；上游版本与许可证在 `references/sources.json` 中仍为待核验状态，落地前必须固定 commit 并核对许可证。
> 风险提示：DeepSeek Harness 是 developer preview，README 明确会有 breaking changes；Super-agent 是 research prototype。不要把本项目绑定到上游内部类型或文件格式，只映射概念。
>
> 文档关系：本文是设计权威；[kernel-api-contract.md](kernel-api-contract.md) 是 P1 实现契约；[backend-implementation-plan.md](backend-implementation-plan.md) 是执行顺序与验收。三者冲突时以本文为准，先改契约再改代码。

## 1. 结论

1. **保持现有三组 Maven 模块**：`agent-core`、`plugins/*`、`backend`。不在第一阶段新增业务模块，用 package 边界表达内核、适配器与产品切片；唯一例外是 test-only 的 `agent-core-testkit`，用于共享 `ScriptedLlmAdapter`/`FakeSessionStore` 等测试替身。
2. **不照搬 Cordis 动态插件系统**。DeepSeek Harness 的 “everything is a plugin” 在 Java 中翻译为 **编译期 seam**：接口定义在 `agent-core`，实现在 `plugins/*` 或 `backend`，由 `bootstrap` 显式装配。这样可以获得可替换性与可测试性，同时避免 OSGi、动态类加载、HMR 和补丁代数带来的复杂度。
3. **会话事件日志是执行真相，消息表只是查询投影**。事件追加、投影更新、会话版本检查、用量记录、效果入队必须在同一个短事务中完成；提交之后再广播 SSE。
4. **一个 turn = 0..n 个 step；一个 step = 一次模型请求 + 它发起的工具调用**。用户输入先落库，模型执行异步进行；两者不混在同一个请求里。
5. **每个会话单写者串行执行，不同会话并行**。SQLite 单连接只用于短事务；模型流、审批等待、Shell、媒体调用期间不得持有连接。
6. **取消令牌贯穿模型流、工具、审批和压缩**。崩溃恢复只补齐 `interrupted` 事件，不自动重放付费模型调用或 Shell 等副作用。
7. **配置按快照绑定**：每轮开始冻结 provider、model、prompt、tools、skills、approval policy、limits，并写入 `request/header` 与 `request/context` 事件；运行中改配置只影响下一轮。
8. **工具走守卫管线**：`pre-execute → guard → execute → post-execute → finalize`。审批是管线中的 `ask` 分支，不是散落在业务层的 `if`。
9. **backend 按 feature 切片组织**：每个切片包含 `api / application / domain / infrastructure`。`chat` 是第一个完整切片，其余功能按需填充。
10. **超智能体编排是第二阶段的可选层**（Trigger/Thread/Eval/Decision/Ticket/Mandate/Assignment/effects/outbox/watchdog），不进入 v1 聊天主链路。
11. **边界要能被构建失败**：`maven-enforcer-plugin` 禁止 `agent-core`/`plugins` 依赖 Spring，ArchUnit 禁止跨 feature 访问 infrastructure，禁止 plugin 依赖 backend。

评审后追加的三条硬约束：

12. **发送消息的 202 阶段只写 `inbox_items`**，不写 `message_nodes`/`messages`；消息投影必须与 `turn/start` + `user/message` 在同一提交事务中创建。
13. **所有会话写命令走同一个 per-session 串行队列**（发送、取消、审批决定、分支切换、fork、手动压缩）。HTTP 层只做校验与信号，不直接追加事件。
14. **分支切换用事件日志上的 `SurfaceReplace` 遮蔽旧分支**，原始事件永不删除；`messages` 是每个节点的内容投影，不是“唯一当前视图”。

## 2. 参考架构的取舍

### 2.1 DeepSeek Harness 的核心不变量

| 不变量 | 含义 | 在本项目中的落点 |
| --- | --- | --- |
| Model + Harness = Agent | 模型只是推理引擎，harness 负责循环、上下文、工具、审批、持久化 | `agent-core` + `backend` 的产品外壳 |
| 一切皆插件 / seam | 模型适配器、工具、会话日志、循环本身都可替换 | Java interface + 编译期实现 + `bootstrap` 装配 |
| append-only SessionEvent log | 模型可见的历史必须能从事件日志重建 | `session_events` 表 + `Session.deriveMessages()` |
| turn / step | 一个 turn 可包含多个 step；step 是一次模型请求及其工具 | `AgentLoop` 的 `TurnDriver` / `StepDriver` |
| request freeze | 请求发出后不可变；重试不重新组装 | `LlmRequest` 不可变记录 + `request/header` 事件 |
| guarded tool pipeline | 工具调用经过 pre/execute/post 与 guard | `ToolPipeline` + `ToolGuard` |
| approval 审计事件 | `approval/asked` 与 `approval/decided` 成对进入日志 | `approval` 包 + `tool_approvals` 表 |
| compaction 保留原文 | 压缩只改投影视图，不删除原始事件 | `compaction/*` 事件 + surface replacement |
| scope / 生命周期 | 每个 Agent 有独立可回滚的注册作用域 | `AgentScope` + `Registration` |
| 存储与执行分离 | 会话日志、文件系统、沙箱是不同 seam | `SessionStore`、`ExecutionEnvironment`、`Workspace` 端口 |

### 2.2 dsh-superagent-prototype 的核心不变量

| 不变量 | 含义 | 在本项目中的落点 |
| --- | --- | --- |
| 注意力预算 | “Take as little human attention as possible” | 只有审批、提问、失败才进入待办；其余静默执行 |
| Trigger → Thread → Eval → Decision → Ticket | 外部事件先归线程，再评估是否接单 | `orchestration` 包的领域模型 |
| Mandate / Policy | 授权是 host 控制的，内容不能提权 | 每个 Ticket 绑定版本固定的授权（可撤回，恢复前重新校验） |
| Assignment | 一个 Ticket 可以多次绑定 Worker 执行 | `worker-<ticketId>` 会话 + 独立工作目录 |
| 单写者 Engine + 五部分 | Intake/Evals/Tickets/Assignments/Replies | `Engine` + `EnginePart`，一个事务改一组聚合 |
| stored work first | 效果先落库，提交后执行 | `effects` / `outbox` 表 + after-commit 派发 |
| Router / Worker 分离 | 主会话负责交互与分派，Worker 上下文隔离 | 主聊天会话 + 每个 Ticket 独立 Worker 会话 |
| Runtime 恢复中断的 Assignment（本项目称 Recovery） | 崩溃后重启，标记中断，不盲目重放 | `RecoveryService` + `InstanceLock` |

### 2.3 翻译到 Java 的规则

| DeepSeek 概念 | Java 17 实现 |
| --- | --- |
| Cordis context / service | `AgentScope` + 类型化 `ServiceKey<T>` + `Registration` |
| branded types（SessionId 等） | `record` 包装 + 紧凑构造器校验；不依赖 TypeScript 的编译期 brand |
| `AsyncIterable<StreamChunk>` | `Flow.Publisher<StreamChunk>` 或 `LlmStream` + `StreamListener` |
| `AbortSignal` | `CancellationToken` + `AutoCloseable` 监听器 |
| discriminated union | `sealed interface` + `record`，Java 17 用 `instanceof` 模式匹配分支 |
| JSONL generation / migration | SQLite 事件表 + `session_format_version` + `EventUpcaster` 链 |
| plugin profile / patch | `config/plugin-profiles/*.yaml` + `PluginCatalog`，启动时装配，不支持热插拔 |
| 30 分钟后台任务上限 | 先做成可配置的 `Jobs` 限制，不把研究原型的数值写死 |
| Web Client slots | 前端职责，后端只提供 projection 与 event stream |

### 2.4 明确不照搬的部分

- **Cordis 动态插件、HMR、profile patch 代数**：Java 侧用编译期模块 + 配置选择即可，动态类加载会破坏可测试性和依赖边界。
- **多来源 IM（GitHub/Feishu）语义**：RikkaHub 首先是本地单用户产品；`Source` 端口保留，但 v1 只实现 ChatSource，Webhook/定时源后续再加。
- **完整 Super-agent 的 Eval 双层判定**：v1 用户显式发起后台任务，不需要 LLM 评估“是否接单”；Eval 作为后续增强。
- **动态 Worker slots、30 分钟限制、订阅直通**：可以借鉴，但要按本地单用户场景简化。
- **Spring AI / LangChain4j**：它们会接管模型调用与上下文管理，和本项目的 harness 语义冲突；模型适配器直接基于 `HttpClient` + Jackson 实现。
- **JPA / ORM**：继续使用 `JdbcClient` + 手写 SQL，保持事件日志和投影的可控性。

## 3. Maven 模块与依赖

```text
rikkahub-parent (pom)
├── agent-core                    普通 Java 17 库，Harness 内核
├── plugins (pom)
│   ├── model-providers           模型协议适配器
│   ├── search-providers          搜索适配器
│   ├── mcp-client                MCP 传输与工具适配
│   ├── workspace-runtimes        文件系统 / 进程 / 终端 / 沙箱
│   ├── speech-providers          TTS / STT
│   ├── media-providers           图像 / 视频生成
│   └── builtin-tools             内置工具定义
├── backend                       唯一 Spring Boot 入口与产品实现
└── agent-core-testkit            test-only，test-jar；只依赖 agent-core
```

依赖方向（必须由构建强制）：

```text
backend ──→ agent-core
backend ──→ plugins/*
plugins/* ──→ agent-core
agent-core ──→ JDK + Jackson + SLF4J（不得依赖 Spring、HTTP server、SQL、产品业务）
plugins/*  ──→ 不得依赖 backend
agent-core ──→ 不得依赖 backend 或 plugins
```

当前构建基线为 Java 17 / Spring Boot 4.1.2，Boot BOM 管理 Jackson 3.1.7 和 JUnit Jupiter 6.0.3。Jackson JSON 类型使用 `tools.jackson`，注解保留 `com.fasterxml.jackson.annotation`；Java 时间支持已包含在 Jackson 3 databind 中，不再单独引入 Jackson 2 的 JSR-310 模块。历史阶段记录中的版本只描述当时验收，不覆盖根 pom 的当前版本。

`agent-core` 允许的最小第三方依赖：

```xml
<dependency>
  <groupId>tools.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-api</artifactId>
</dependency>
```

如果坚持零第三方依赖，可把 JSON 编解码抽象为 `port.JsonCodec`，由 backend 用 Jackson 实现；但不要把 Spring 带进内核。第一版建议直接用 Jackson，减少无谓的适配层。

未来只有当 `agent-core` 超过约 2 万行或需要独立版本化时，才拆成 `agent-api` / `agent-runtime` / `agent-orchestrator` 三个 Maven 模块。现在拆模块只会增加构建与装配成本。

### 3.1 现状盘点与改造动作

| 现状 | 保留/改造 | 下一步 |
| --- | --- | --- |
| `backend/Main.java` + `application.properties` | 保留入口与端口约定 | 增加 `bootstrap` 包与显式配置 |
| `platform/storage` 四个类 | 保留；扩展为事件提交与快照协调 | 新增 `SessionCommitService`、`JdbcSessionStore`、`TransactionRunner` |
| `features/*` 只有 `package-info.java` | 保留包名，按切片填充 | 先做 `chat`，再按 P4/P5 顺序补 |
| `agent-core/*` 只有 `package-info.java` | 保留现有 13 个包名 | 按 §4.1 增加类型与子包 |
| `plugins/*` 只有 `package-info.java` | 保留模块名与“只依赖 agent-core” | 每个模块先落一个接口实现与契约测试 |
| `contracts/http/openapi.yaml` 的 `paths: {}` | 保留；不要提前引入生成器 | 第一个端点确定后补 OpenAPI 与生成脚本 |
| `config/*` 空目录 | 保留模板/示例/Agent/profile 分工 | 先加 `config/plugin-profiles/local.yaml` 与 `config/agents/default.yaml` |
| `prompts/*` 空目录 | 保留五类提示词分工 | 先加 system 与 compression 的最小模板 |
| `skills/*` 空 manifest | 保留 manifest schema | 先加一个内置技能样例 |
| `tests/*` 空目录 | 保留分层 | 先加 `agent-core` 单测与 `backend` 迁移测试 |
| `data/*` 运行目录 | 保留现有 `StoragePaths` | 增加 `data/.lock`、`data/workspace/tickets`（P5） |

## 4. agent-core：Harness 内核

### 4.1 包结构

```text
agent-core/src/main/java/com/rikkahub/agent/
├── api/                    对外契约：Agent、AgentRuntime、AgentFactory、AgentHandle、AgentOptions、取消
├── loop/                   AgentLoop、TurnDriver、StepDriver、Inbox、Hook 链
├── session/                会话、事件日志、surface、投影、fork/resume、格式版本
│   └── event/              全部 SessionEvent payload record
├── message/                Message、ContentBlock、StreamChunk、TokenUsage、MessageSource
├── model/                  LlmAdapter、LlmRequest、LlmStream、ModelRef、RetryPolicy、LlmFailure
├── tool/                   ToolDefinition、ToolSchema、ToolRegistry、ToolPipeline、ToolGuard
├── approval/               ApprovalPolicy、ApprovalRequest、ApprovalOutcome、ApprovalService
├── context/                SystemPromptAssembler、PromptSection、TokenMeter、CompactionPolicy、Compactor
├── execution/              ExecutionEnvironment、Workspace、FileSystem、ProcessRunner、Terminal、JobManager
├── event/                  AgentEvent、AgentEventSink、AgentEventListener（进程内实时事件）
├── scope/                  AgentScope、ServiceKey、Registration、Disposable
├── plugin/                 AgentPlugin、PluginCatalog、PluginContext、PluginContribution
├── port/                   Clock、IdGenerator、SessionStore、InboxStore、TelemetrySink、
│                           PromptSource、SkillSource、MemoryStore、SearchPort、McpPort、
│                           SpeechPort、MediaPort、CredentialProvider
├── config/                 AgentConfig、AgentConfigSnapshot、ModelSelection、ContextPolicy、Limits
├── skill/                  Skill、SkillSummary、SkillSelection（SkillCatalog 在 backend）
├── subagent/               SubagentProvider、SubagentRequest、SubagentResult（第二阶段）
└── orchestration/          Trigger/Thread/Ticket/...、Engine、EffectQueue、Outbox、Source（第二阶段）
```

`api` 只放稳定契约；`loop` 是实现，扩展方依赖 `api` 而不是 `loop`，保证循环可替换。

`AgentScope` 是 Cordis context 的 Java 等价物：注册即副作用，关闭即回滚，子作用域随 Agent 销毁。

```java
public final class AgentScope implements AutoCloseable {
    public <T> Registration provide(ServiceKey<T> key, T service);
    public <T> Optional<T> find(ServiceKey<T> key);
    public AgentScope child();
    public boolean isClosed();
    @Override public void close();          // 逆序注销本作用域的注册
}

public interface Registration extends AutoCloseable {
    @Override void close();                 // 幂等；只注销，不关闭共享资源
}
```

P1 采用具体 `final class`（可直接实例化、便于纯内存测试）；如果后续出现第二种作用域实现，再抽 `AgentScope` 接口，不在 P1 预先引入空接口。

`AgentScope` 只用于“每个 Agent 独立注册的工具/插件能力”，不是通用 DI 容器；核心协作者仍由 `AgentRuntime` 用构造器显式组合。契约：线程安全；父作用域先关闭子作用域；`close()` 幂等；`Registration.close()` 只解除注册，不关闭 MCP 连接、HTTP client 等跨 Agent 共享资源，共享资源由 backend 的 `PluginLifecycle` 统一关闭。

### 4.2 关键接口

```java
public interface Agent {
    AgentId id();
    SessionId sessionId();
    AgentStatus status();                 // IDLE / RUNNING / DISPOSED
    AgentContext context();

    void send(InboxItem item, InboxTarget target);   // next-turn / next-step
    void followup(InboxItem item);                   // 新的一轮
    void steer(InboxItem item);                      // 最近一个 step 边界
    void inject(InboxItem item);                     // 只注入上下文，不唤醒
    CancelResult cancel(CancelOptions options);
    CompletableFuture<Void> whenIdle();
    void runMaintenance();
}

public record AgentHandle(Agent agent, AutoCloseable disposer) implements AutoCloseable {
    @Override public void close() throws Exception { disposer.close(); }
}

public interface AgentRuntime {
    AgentHandle create(AgentSpec spec);
    AgentHandle resume(SessionId sessionId, AgentOptions options);
    Optional<Agent> find(AgentId id);
    List<Agent> list();
}
```

`send/followup/steer/inject` 只入队，不直接写事件：

- `followup` = 新 turn；`send(item, NEXT_STEP)` = 当前 turn 的下一个 step；`steer` = 最近一个 step 边界。
- `inject` = 追加一条持久的 `user/message` 或 `system/message`，带 `wake=false`，在下一个请求前生效。只要它可能进入模型历史，就必须落事件，不能只放内存。
- 四个方法最终都映射为 `PendingInput`，由 `InboxStore` 持久化；`InboxItem`（运行时）与 `PendingInput`（持久化）使用同一套序列化映射。

```java
public record AgentOptions(
    AgentConfigSnapshot config,
    ExecutionEnvironment environment,
    CancellationToken parentSignal
) { }

public record AgentConfigSnapshot(
    long version,
    String hash,
    Instant frozenAt,
    ModelRef model,
    GenerationOptions generation,
    ContextPolicy context,
    ApprovalPolicy approval,
    ToolPolicy tools,
    List<String> skills,                   // P1：技能标识；P4 接入 SkillId
    String promptProfile,
    Duration turnTimeout,
    int maxSteps
) { }
```

```java
public final class CancellationToken {
    public boolean isCancelled();
    public CancelCause cause();
    public AutoCloseable onCancel(Runnable listener);
    public void throwIfCancelled();                 // throws CancelledException
    public CancellationToken child();
}
```

```java
public interface SessionStore {
    SessionSnapshot load(SessionId id);
    SessionSnapshot loadRange(SessionId id, long afterSeq, int limit);
    SessionCommitResult commit(SessionCommit commit);   // 唯一写入口
    List<SessionSummary> list();
}

public interface InboxStore {
    void enqueue(PendingInput input);
    ClaimedInput claim(SessionId id, String owner, Duration lease);
    ClaimedInput claim(SessionId id, InboxTarget target, String owner, Duration lease);
    void complete(ClaimToken token, InputId inputId);   // 与 turn/start + user/message 同事务
    void release(ClaimToken token, boolean retryable);
    List<PendingInput> listPending(SessionId id);
}
```

`SessionStore.commit` 是唯一写路径。`NewSessionEvent` 只描述“发生了什么”，**不携带 `seq`/`time`**；序号与时间由存储层在事务内连续分配，返回 `SessionCommitResult(committedSeq, lastSeq, metadataVersion)`。会话创建与 fork 不在 `SessionStore` 上另开入口，而是 `SessionCommitService.createSession(...)` / `fork(...)` 的组合操作，内部仍调用同一个 `commit`。

`SessionCommit` 的正式形状：

```java
public record SessionCommit(
        SessionId sessionId,
        long expectedSeq,                       // 0 表示新会话或 fork
        SessionHeader header,                   // 仅创建/fork 时非空
        List<NewSessionEvent> events,           // 正常追加；store 分配 seq/time
        List<SessionEvent> fixedEvents,         // 仅 fork：保留原 seq 的前缀
        List<UsageRecord> usage,
        List<EffectIntent> effects,
        InboxConsumption inboxConsumption,      // 可空
        SessionStatusTransition status          // RUNNING / IDLE / ENDED
) { }
```

约束：`events` 与 `fixedEvents` 互斥；`fixedEvents` 只允许出现在 `expectedSeq == 0` 的 fork 提交中，必须从 seq 1 连续递增，store 校验后原样写入（不得重新编号）；创建与 fork 共用这一个提交入口，store 不提供第二个 `create`/`append` 方法。

`InboxStore` 是“输入保存与模型执行分开”的落点：用户消息先进入 `inbox_items`（PENDING），`claim` 在单事务内把 PENDING 原子地转为 IN_PROGRESS，并写 `claimed_at`/`lease_until`/`claim_token`/`attempts`。AgentLoop 打开 turn 时，`complete` 与 `turn/start` + `user/message` 在同一事务中把 IN_PROGRESS 转为 CONSUMED；崩溃恢复把过期 lease 重置为 PENDING，因此既不会丢输入，也不会重复计费。

```java
public interface LlmAdapter {
    ProviderInfo provider();
    List<ModelInfo> models();
    LlmStream stream(LlmRequest request, CancellationToken signal);
}

public interface LlmStream extends AutoCloseable {
    void subscribe(StreamListener listener);   // onChunk / onFailure / onComplete
}

public sealed interface StreamChunk {
    record TextDelta(String text) implements StreamChunk { }
    record ReasoningDelta(String text) implements StreamChunk { }
    record ToolCallDelta(int index, String callId, String toolName, String argumentsFragment)
            implements StreamChunk { }
    record Usage(TokenUsage usage) implements StreamChunk { }
    record Finished(FinishReason reason) implements StreamChunk { }
    record Failed(String code, String message, boolean retryable) implements StreamChunk { }
    record Cancelled() implements StreamChunk { }
}
```

### 4.3 事件模型

```java
public sealed interface SessionEventPayload permits
        TurnStart, TurnEnd, StepStart, StepEnd,
        UserMessageEvent, SystemMessageEvent, AssistantMessageEvent, AssistantAttemptEvent,
        ToolCallEvent, ToolResultEvent, RequestHeaderEvent, RequestContextEvent,
        ApprovalPolicyEvent, ApprovalAskedEvent, ApprovalDecidedEvent,
        CompactionStartEvent, CompactionSummaryEvent, CompactionEndEvent,
        BranchSwitchEvent, SessionEndSeedEvent { }

public record SessionEvent(
        long seq,
        Instant time,
        SessionEventPayload data,
        SurfaceMeta surface,          // surface 事件必填，其余为 null
        boolean ignorable
) { }

public record NewSessionEvent(
        SessionEventPayload data,
        SurfaceMeta surface,
        boolean ignorable
) { }                                  // seq/time 由存储层分配

public record SurfaceMeta(SurfaceOp op, List<Long> sourceEventSeqs) { }

public sealed interface SurfaceOp permits SurfaceAppend, SurfaceReplace {
    record SurfaceAppend() implements SurfaceOp { }
    record SurfaceReplace(long startSeq, long endSeq) implements SurfaceOp { }
}
```

`SurfaceReplace.startSeq/endSeq` 是本项目定义的**被遮蔽事件 seq 闭区间**（不是上游的 surface 位置），`sourceEventSeqs` 是权威集合；start/end 只用于快速查询和审计。连续两次压缩、分支切换都必须允许 start > end 的“空遮蔽”，并用 `sourceEventSeqs` 复核。

核心事件语义：

| 事件 | 作用 |
| --- | --- |
| `turn/start` / `turn/end` | 打开/关闭一轮；turn 可以没有 step（被拒绝、空输入、取消） |
| `step/start` / `step/end` | 一次模型请求及其工具执行 |
| `user/message` | 人类输入、注入上下文、steering、目标续写 |
| `system/message` | 渲染后的系统提示词，首个是 surface 节点 0 |
| `assistant/message` | 组装后的助手消息，含流证据摘要（finish reason、usage、工具增量；大块落 spill 引用） |
| `assistant/attempt` | 失败/重试/取消/流错误的尝试，不进入模型历史 |
| `tool/call` | 模型产出的原始工具参数 JSON，不解析 |
| `tool/result` | 模型可见的工具结果，`error` 仅在 `isError=true` 时出现 |
| `request/header` | 下一次请求的完整信封（config、tools），log-only |
| `request/context` | provider/model/contextWindow/systemPromptUpdate 路由元数据 |
| `approval/policy` | 会话审批策略覆盖；最后一条生效，变更写新快照 |
| `approval/asked` / `approval/decided` | 审批审计对，log-only |
| `compaction/start` / `summary` / `end` | 压缩锁、摘要、替换范围 |
| `branch/switch` | 切换当前分支：目标节点、遮蔽 seq 区间、前后高水位；log-only |
| `session/end-seed` | 继承/恢复历史与实时工作的边界 |

持久化矩阵（哪些事件必须在副作用之前落盘）：

| 阶段 | 必须先提交的事件 | 之后的副作用 |
| --- | --- | --- |
| 用户输入 | `turn/start` + `user/message` + inbox CONSUMED | 组装 prompt、调用模型 |
| 模型请求 | `request/header` + `request/context` | 发起流式请求 |
| 工具调用 | `tool/call` | 执行文件/Shell/网络 |
| 审批 | `approval/asked` | 等待用户 |
| 审批决定 | `approval/decided` | 继续工具管线 |
| step 结束 | `step/end` | 下一个 step |

`assistantDelta`、`toolOutputDelta` 是临时流，不逐字落库；`assistant/message`、`tool/result` 结算时一次性写入。任何 `tool/call` 必须恰好产生一个 `tool/result`，无论成功、失败、拒绝、超时还是取消；失败结果 `isError=true` 并携带结构化错误，同样进入模型历史，否则模型无法自纠。

事件 JSON 使用显式的 `type` 判别字段（Jackson `@JsonTypeInfo` + `@JsonSubTypes`），不要依赖类名或运行时类型推断；未知事件只有 `ignorable=true` 时才允许跳过，否则拒绝重建会话。

事件格式迁移：`agent_sessions.format_version` 标记会话格式；每个 payload 内部带 `schemaVersion`；`EventUpcaster` 按 `(type, schemaVersion)` 链式升级，旧日志先 upcast 再反序列化。`EventCodec` 使用专用 `ObjectMapper`（JavaTime、稳定字段顺序、禁止 class-name 推断），并配 golden 文件覆盖“旧格式读取”和“未知非 ignorable 事件拒绝”。

`turn/end` 的原因：

```java
public sealed interface TurnEndReason permits TurnEndReason.Completed, TurnEndReason.Aborted,
        TurnEndReason.Blocked, TurnEndReason.Errored, TurnEndReason.MaxTokens,
        TurnEndReason.Interrupted, TurnEndReason.Forked, TurnEndReason.Shutdown {
    record Completed() implements TurnEndReason { }
    record Aborted(String reason) implements TurnEndReason { }
    record Blocked(String reason) implements TurnEndReason { }
    record Errored(String code, String message) implements TurnEndReason { }
    record MaxTokens() implements TurnEndReason { }
    record Interrupted() implements TurnEndReason { }
    record Forked() implements TurnEndReason { }
    record Shutdown() implements TurnEndReason { }
}
```

`Interrupted` 只能由崩溃恢复合成，`Forked` 只能由分支/fork 路径合成，`Shutdown` 只能由优雅停机路径合成；正常循环不会发送这三个值。优雅停机不要复用 `Interrupted`。

### 4.4 循环语义

```text
claim input + queued message
snapshot model selection；组装 system prompt 与允许的工具 schema
agent/pre-step（accept / rewrite / reject）
step/start；组装并冻结 LlmRequest；request/header；request/context
llm/stream 流式输出 → 实时 assistant-stream 事件（不落库）
组装 assistant/message；记录 assistant/attempt（失败时）
tool/call → tools/pre-execute → guard → tools/execute → tools/post-execute → tool/result
step/end
如果还有工具结果或新输入 → 下一个 step
agent/turn-stopping → turn/end
```

关键规则：

1. `agent/pre-step` 是同步 waterfall：监听器调用一次 `next()` 继续，或显式 rewrite/reject 终止后续钩子。无决策返回、拒绝或重写后消息为空时，turn 以无 step 方式关闭。
2. 请求一旦冻结，重试只刷新运行时事实，不重新组装 prompt、不重新执行 pre-step。
3. 流式 token 只走进程内事件到 SSE，不逐字写库；在 `assistant/message` 结算时一次性写入流证据摘要（finish reason、usage、工具增量），完整流落 `data/streams/` 并只存引用。
4. 工具调用参数永远保持原始 JSON 字符串；只有工具执行器解析。
5. `tool/call`、审批请求/决定、step 结束、压缩标记、`request/header` 都是持久化检查点，必须在对应副作用或下一次请求之前提交；只有 `assistantDelta` / `toolOutputDelta` 这类流式片段可在内存中合并。
6. 取消发生在模型流、工具执行或审批等待时，统一走 `CancellationToken`；已开始的工具仍要 drain，取消不能伪造“未执行”。

### 4.5 工具管线与审批

```java
public interface ToolDefinition {
    ToolSchema schema();                       // 只有 name/description/parameters 暴露给模型
    ToolOutput output();
    ToolResult execute(JsonNode arguments, ToolRunContext context);
    default Duration timeout() { return Duration.ofSeconds(120); }
    default boolean concurrencySafe(JsonNode arguments) { return false; }
    default List<ContentBlock> finalizeContent(ToolExecution execution, ToolResult result) {
        return result.content();
    }
}

public interface ToolRegistry {
    List<ToolSchema> schemas(ToolScope scope);
    Optional<ToolDefinition> find(String name, ToolScope scope);
    AutoCloseable register(ToolDefinition tool, ToolScope scope);
}

public interface ToolPipeline {
    ToolResult execute(ToolCall call, ToolExecutionScope scope, CancellationToken signal);
}

public interface ToolHooks {
    default ToolDecision preExecute(ToolCall call, ToolRunContext context) { return ToolDecision.allow(); }
    default ToolDecision guard(ToolCall call, ToolRunContext context) { return ToolDecision.allow(); }
    default void postExecute(ToolCall call, ToolResult result, ToolRunContext context) { }
    default List<ContentBlock> projectContent(ToolCall call, ToolResult result) { return result.content(); }
}
```

管线顺序：`preExecute → guard → execute → projectContent → postExecute → finalizeContent → tool/result`。`ToolPipeline` 是唯一执行入口；`ToolRegistry` 只负责 schema、查找与注册，不再暴露第二个 execute 入口。

- `preExecute` / `guard` 返回 `allow / deny / ask / cancel`；`ask` 进入审批，`deny` 直接生成 `isError=true` 的 `tool/result`。
- **每个 `tool/call` 必须恰好产生一个 `tool/result`**：成功带 output，失败/拒绝/超时/取消带 `isError=true` 与结构化 error；两者都进入模型历史，否则模型无法自纠。
- 工具异常转换为结构化错误，不终止整个 turn。
- `concurrencySafe=true` 的工具可并行；未声明或抛异常一律 exclusive，并形成顺序屏障。
- 工具超时是协作式的，不把 timeout 泄漏给模型。

审批：

```java
public enum ApprovalPolicy { ASK, NEVER }

public sealed interface ToolDecision permits ToolDecision.Allow, ToolDecision.Deny,
        ToolDecision.Ask, ToolDecision.Cancel {
    record Allow() implements ToolDecision { }
    record Deny(String reason) implements ToolDecision { }
    record Ask(String reason) implements ToolDecision { }
    record Cancel() implements ToolDecision { }
}

public record ToolPolicy(
        PermissionPreset preset,          // READ_ONLY / WORKSPACE_WRITE / FULL_ACCESS
        List<ToolRule> rules              // 有序；tool name + path glob / command regex -> ALLOW/ASK/DENY
) { }

public record ApprovalRequest(
        String id, String sessionId, String callId,
        String toolName, String argumentsJson, String reason, Instant createdAt
) { }

public interface ApprovalBroker {
    ApprovalOutcome request(ApprovalRequest request, CancellationToken signal);
}

public sealed interface ApprovalOutcome permits ApprovalOutcome.AllowedOnce,
        ApprovalOutcome.Rejected, ApprovalOutcome.Cancelled, ApprovalOutcome.Unavailable {
    record AllowedOnce() implements ApprovalOutcome { }
    record Rejected(String reason) implements ApprovalOutcome { }
    record Cancelled(String reason) implements ApprovalOutcome { }
    record Unavailable(String reason) implements ApprovalOutcome { }
}
```

P1 用 `String` 承载 approval id；转为 `ApprovalId` 强类型、增加 `turn`/`argsDigest`/`expiresAt` 属于 P3 的审批投影增强，不改变 `ApprovalOutcome` 四态语义。

- `ASK` 走审批通道；`NEVER` 直接 `rejected`，不允许后置监听器绕过。
- 审批策略解析顺序：会话 `approval/policy` 覆盖 > assistant 配置 > 全局默认；解析结果在 turn 开始时冻结。
- `approval/asked` 与 `approval/decided` 成对写入会话日志；`tool_approvals` 是查询投影。
- 审批等待不持有数据库连接；超时、取消、进程重启分别落到 `cancelled` / `unavailable`，绝不自动批准。恢复时为每个未配对的 `approval/asked` 追加 `approval/decided {unavailable}`。
- 单次审批只允许 `allowed-once`，且决定是 CAS 幂等操作；重复 POST 只结算一次。永久策略是 session/assistant 配置，不是单次审批结果。

### 4.6 上下文与压缩

- `SystemPromptAssembler` 按 `PromptSection.order` 升序拼接；同名 section 直接失败；相等 order 按 name 排序。
- section 来源：内置提示词、assistant 配置、技能目录、记忆检索结果、运行时上下文（cwd、时间、工具说明）。
- 首个渲染结果写入 `system/message` surface 节点 0；后续变化按 `SystemPromptUpdatePolicy` 处理：`REPLACE_IN_PLACE`（默认，追加 surface replace 遮蔽旧系统消息）或 `APPEND_TO_HISTORY`（保留旧版本，新版本作为新节点）。策略必须写入配置快照并在测试中固定。
- `TokenMeter` 负责估算与实际 usage 归一化；`CompactionPolicy` 只看阈值与窗口，不硬编码上游数值。
- 压缩序列：`compaction/start` → 摘要模型调用 → `compaction/summary` → 追加带 `SurfaceReplace` 的 `user/message` → `compaction/end`。
- 压缩只改 surface 投影，不删除原始事件；`compaction/summary` 记录 shadowed range、shadowed seqs、token 数、provider/model/usage。
- `CompactionEndEvent` 必须带 `error` 字段（成功为 null）。摘要失败时保留 `compaction/start` 与已产生的部分，写 `compaction/end {error}`，原文不受影响。
- 崩溃留下未配对的 `compaction/start` 时，恢复标记中断并阻塞新的自动压缩，但提供手动出口：`POST /api/v1/sessions/{id}/compaction` 重试、`POST /api/v1/sessions/{id}/compaction/{seq}/abandon` 清理未配对锁。未配对 `start` 如果早于更新的 `session/end-seed`，视为过期，不再阻塞。

## 5. plugins：能力适配器

| 模块 | 实现的端口 | 主要类 | 说明 |
| --- | --- | --- | --- |
| `model-providers` | `model.LlmAdapter` | `OpenAiCompatibleAdapter`、`AnthropicAdapter`、`GeminiAdapter`、`DeepSeekAdapter`、`OllamaAdapter`、`StreamAssembler`、`RetryClassifier`、`ModelCatalog` | 把各家流式协议归一化为 `StreamChunk`；finish 是语义结束，允许后续 usage，在 onComplete 前结算 |
| `builtin-tools` | `tool.ToolDefinition` | `ReadFileTool`、`WriteFileTool`、`EditFileTool`、`GlobTool`、`GrepTool`、`ShellTool`、`TerminalTool`、`WebFetchTool`、`TodoTool`、`SkillTool` | 只依赖 `agent-core` 的端口，不直接读 Spring 配置 |
| `workspace-runtimes` | `execution.ExecutionEnvironment` | `LocalWorkspace`、`LocalFileSystem`、`LocalProcessRunner`、`LocalTerminal`、`LocalJobManager`、`DockerWorkspace`（后续） | 文件、Shell、终端共享同一工作区根与路径策略 |
| `mcp-client` | `port.McpPort` + `tool.ToolDefinition` | `McpClient`、`StdioTransport`、`HttpSseTransport`、`McpToolAdapter`、`McpResourceAdapter` | 工具名加 `mcp__<server>__<tool>` 命名空间；连接生命周期由 backend 管理 |
| `search-providers` | `port.SearchPort` | `BraveSearchAdapter`、`TavilySearchAdapter`、`SearxngAdapter` | 统一结果结构、引用与截断 |
| `speech-providers` | `port.SpeechPort` | `OpenAiSpeechAdapter`、`ElevenLabsAdapter`、`WhisperAdapter` | TTS/STT 异步任务 |
| `media-providers` | `port.MediaPort` | `OpenAiImageAdapter`、`VideoProviderAdapter` | 生成任务落 `media_tasks`，不阻塞聊天 |

装配规则：

```text
config/plugin-profiles/*.yaml
        │
        ▼
backend/bootstrap/PluginConfiguration
        │  读取 profile，构造 plugin 实例
        ▼
PluginCatalog
        │  install(PluginContext)
        ▼
AgentScope  ←── 每个 Agent 独立作用域，dispose 时回滚注册
```

- 插件模块不包含 Spring 注解；backend 用普通 Java 构造器创建它们。
- 开关和参数来自 `config/plugin-profiles/*.yaml`；变更需要重启，不支持运行时热插拔。
- 插件只能通过 `PluginContext` 注册能力，不能直接拿 `ApplicationContext` 或 `JdbcClient`。

## 6. backend：Spring 组合根与产品切片

### 6.1 bootstrap

```text
backend/src/main/java/com/rikkahub/
├── Main.java                        保持现有入口
├── bootstrap/
│   ├── ModuleConfiguration.java     显式 @Import 各 feature/platform 配置
│   ├── AgentCoreConfiguration.java  AgentRuntime、SessionStore、Clock、IdGenerator 等 Bean
│   ├── PluginConfiguration.java     读取 plugin profile，构造 PluginCatalog
│   ├── FeatureConfiguration.java    chat/assistants/providers/... 的装配
│   ├── LifecycleConfiguration.java  优雅停机、InstanceLock、RecoveryService
│   └── StartupChecks.java           数据目录、迁移版本、provider 连通性
```

`@SpringBootApplication` 会扫描 `com.rikkahub.**`，但建议每个 feature 用显式 `@Configuration` 暴露 Bean，避免“扫描到就能用”的隐式依赖。

### 6.2 platform

```text
platform/
├── config/          配置分层、快照、plugin profile、JSON schema 校验
├── storage/         现有 StoragePaths/DatabaseConfiguration/DatabaseSnapshotService
│   ├── SessionProjection.java        投影端口（chat 实现，bootstrap 注入）
│   ├── SessionCommitService.java     事件 + 投影 + 版本 + 用量 + 效果的唯一提交入口
│   ├── JdbcSessionStore.java         SessionStore 的唯一 JDBC 实现
│   └── TransactionRunner.java        短事务模板 + SQLITE_BUSY 有界重试
├── recovery/        RecoveryService、RecoveryContributor 接口
├── idempotency/     IdempotencyService、IdempotencyFilter、响应重放
├── http/            ProblemDetail、错误码、TraceId、CORS、请求校验
├── sse/             SseHub、SseConnection、SseReplayService、心跳与背压
├── security/        LocalTokenService、OriginGuard、SecurityHeadersFilter、SecretRedactor
├── credentials/     env / 本地加密文件；API 只返回掩码引用
├── concurrency/     KeyedSerialExecutor、SessionTaskQueue、工具池、调度器
├── json/            ObjectMapper、事件 codec、schema 校验
├── observability/   结构化日志、指标、审计日志、健康检查
└── backup/          BackupCoordinator（数据库快照 + 文件归档）
```

### 6.3 features

| feature | 职责 | 主要依赖 |
| --- | --- | --- |
| `chat` | 会话、消息、分支、流式、取消、审批、附件 | agent-core、assistants、providers、files、skills、memory、mcp |
| `assistants` | Assistant 配置：模型、提示词、工具策略、技能选择 | providers、skills |
| `providers` | Provider 配置、凭据引用、模型目录、连通性测试 | model-providers |
| `extensions` | 插件 profile、启用/禁用、配置 schema | plugins、platform/config |
| `search` | 搜索 provider 配置与搜索结果展示 | search-providers |
| `memory` | 记忆文档、提取任务、检索、用户编辑 | platform/storage、files |
| `skills` | 技能目录、安装/编辑/删除、manifest 校验 | skills/、data/skills |
| `mcp` | MCP server 配置、连接状态、工具/资源发现 | mcp-client |
| `workspace` | 工作区根、文件树、终端、后台任务 | workspace-runtimes |
| `files` | 上传、附件、内容寻址存储、预览 | platform/storage |
| `translation` | 一次性翻译调用（不走 agent loop） | model-providers |
| `speech` | TTS/STT 任务 | speech-providers |
| `media` | 图像/视频生成任务 | media-providers |
| `favorites` | 收藏消息/会话 | chat |
| `usage` | token、成本、按会话/模型聚合 | platform/storage |
| `backup` | 导出/导入、备份状态 | platform/storage、platform/backup |
| `preferences` | 应用设置、语言、主题、默认模型 | platform/config |
| `setup` | 首次启动向导、数据目录检查、provider 测试 | providers |
| `orchestration` | 后台任务、Ticket、Worker、效果面板（第二阶段） | agent-core/orchestration、workspace |

每个 feature 的固定切片模板：

```text
features/<name>/
├── api/              Controller、DTO、DTO mapper
├── application/      用例、事务边界、命令/查询服务
├── domain/           聚合、值对象、仓库端口
└── infrastructure/   JDBC 仓库、外部适配器、SSE 发布器
```

没有持久化的 feature 可以只有 `api/application`。跨 feature 只能调用对方的 `application` 公开服务或共享端口，禁止直接访问对方的 `infrastructure` 或 repository。

### 6.4 chat 切片

```text
features/chat/
├── api/
│   ├── ConversationController.java
│   ├── MessageController.java
│   ├── ApprovalController.java
│   ├── SessionStreamController.java
│   └── dto/
├── application/
│   ├── ConversationCommandService.java
│   ├── ConversationQueryService.java
│   ├── SendMessageHandler.java
│   ├── RegenerateHandler.java
│   ├── ForkConversationHandler.java
│   ├── CancelTurnHandler.java
│   ├── ApprovalApplicationService.java
│   ├── MessageProjectionService.java     实现 platform.storage.SessionProjection
│   └── ChatStreamService.java
├── domain/
│   ├── Conversation.java
│   ├── MessageNode.java
│   ├── ConversationRepository.java
│   ├── MessageNodeRepository.java
│   └── ConversationVersion.java
├── infrastructure/
│   ├── persistence/
│   │   ├── JdbcConversationRepository.java
│   │   ├── JdbcMessageNodeRepository.java
│   │   └── JdbcMessageProjectionRepository.java
│   └── streaming/
│       ├── ChatSsePublisher.java
│       └── AssistantDeltaBridge.java
└── agent/
    ├── ChatAgentService.java              产品用例 → AgentRuntime
    ├── ChatAgentFactory.java              组装 AgentSpec / AgentConfigSnapshot
    ├── ChatToolCatalog.java               按 assistant 策略筛选工具
    ├── ChatApprovalBridge.java            ApprovalBroker 的 SSE/HTTP 实现
    ├── ChatEventBridge.java               AgentEvent → SSE
    ├── ChatPromptProvider.java            PromptSource 实现
    └── ChatRecoveryContributor.java       只提供恢复钩子，不自行合成 interrupted
```

`JdbcSessionStore` 只属于 `platform/storage`；chat 不重复实现会话存储。usage 由 loop 累积后放入 `SessionCommit`，由 `SessionCommitService` 在同一事务写入 `usage_records`，不再有独立的 `UsageSink` bean。恢复统一由 bootstrap 的 `RecoveryService` 调度，各 feature 只实现 `RecoveryContributor`，避免两处都能合成 `interrupted`。

`chat/agent` 是产品与内核之间的防腐层，不能把 `Conversation`、`Assistant` 等产品实体传进 `agent-core`；只传 `AgentSpec`、`AgentConfigSnapshot`、`Message`、`ToolDefinition` 等内核类型。

### 6.5 orchestration 切片（第二阶段）

```text
backend/features/orchestration/
├── api/              Ticket/Thread/Worker 的查询与操作
├── application/      OrchestrationService、PassScheduler、EffectHandlers
├── domain/           （可选）产品侧扩展，核心领域在 agent-core/orchestration
└── infrastructure/   JdbcTicketRepository、JdbcEffectQueue、JdbcOutbox、Source 适配器
```

核心领域放在 `agent-core/orchestration`，因为它不依赖 Spring/产品实体；backend 只提供持久化、HTTP 和具体 Source。若后续这部分膨胀，再拆 `agent-orchestrator` Maven 模块。

## 7. 数据模型与迁移

### 7.1 提交规则

任何一次状态推进都走同一个入口，且由 per-session 串行队列上的唯一写者调用：

```text
SessionCommitService.commit(SessionCommit)
  1. 校验 expectedSeq、幂等键（PATCH 元数据另走 metadata_version 乐观锁）
  2. 由 store 分配连续 seq/time，插入 session_events
  3. 应用投影：message_nodes / messages（PENDING → STREAMING → COMPLETE/INTERRUPTED/ERROR）
  4. 更新 conversations.current_node_id、last_seq、metadata_version
  5. 更新 agent_sessions.status（RUNNING/IDLE/ENDED）与 updated_at/ended_at
  6. 完成 inbox 消费（IN_PROGRESS → CONSUMED）
  7. 插入 usage_records
  8. V10 起 effects 与会话事件同事务写入（stored work first）；P5 Engine 事务同时保存领域变化、effects 和本地回复 outbox，提交后派发
  提交方法返回后（post-commit，连接已归还）：发布 SessionCommitted → SSE 入队 + effect 派发
```

`SessionProjection` 端口定义在 `platform.storage`，由 chat 实现并在 bootstrap 注入；platform 只依赖自己的接口，不反向依赖 feature。

```java
public interface SessionProjection {
    void apply(SessionId sessionId, List<SessionEvent> events, ProjectionContext context);
}
```

约束：

- 事件与投影必须在同一事务；Controller、Service、AgentLoop 不得各自维护消息状态。
- 事务内不调用模型、不等待审批、不执行 Shell、不做媒体生成。
- SQLite 单连接会把写入串行化；事务必须短，禁止在事务内做全量事件加载。
- 流式 token 不逐字写库；在 `assistant/message` 结算时一次性写入。
- **事务同步回调（`AFTER_COMMIT` / `AFTER_COMPLETION`）里不做任何数据库或阻塞操作**：连接可能尚未归还。正确做法是让应用服务在 `@Transactional` 提交方法返回后再调用 `postCommitPublisher.publish(result)`（此时连接已归还）；如果必须用同步回调，只允许投递到一个独立队列，消费者不得 inline 执行，且不得调用 `@Transactional` 服务。`SseEmitter.send` 也只入队。
- `KeyedSerialExecutor` 禁止 inline 执行；任务总是进入有界队列，避免单连接下自锁。
- 所有会话写命令（审批决定、取消、分支切换、fork、手动压缩）都进入同一 per-session 队列；HTTP 线程只做校验与 signal。
- `expectedSeq` 冲突视为编程错误/并发入口漏网：拒绝写入并返回 409；只有 `SQLITE_BUSY` 在 `TransactionRunner` 内有界重试。

### 7.2 表与迁移顺序

| 迁移 | 内容 |
| --- | --- |
| `V1__initialize_storage.sql` | 已存在：`app_settings` |
| `V2__providers_and_assistants.sql` | `providers`、`assistants`、`idempotency_keys` |
| `V3__conversations_and_message_nodes.sql` | `conversations`、`message_nodes`、`messages`、`inbox_items` |
| `V4__agent_sessions_and_events.sql` | `agent_sessions`、`session_events` |
| `V5__usage_records.sql` | `usage_records` |
| `V6__repair_message_content_discriminators.sql` | 修复旧消息投影中缺失的 content/tool-call 类型判别字段 |
| `V7__tool_approvals_and_outputs.sql` | `approvals`（持久审批投影）与 `tool_outputs`（会话绑定输出索引） |
| `V8__conversation_tool_policy_override.sql` | `conversations` 的会话级工具/审批策略覆盖列 |
| `V9__memory_documents.sql` | `memory_documents` 与 FTS5 `memory_fts`；正文仍以 `data/memory/*.md` 为准 |
| 后续版本（P4/P5） | `files`（附件上传仍未实现）；`installed_skills`、`mcp_servers`、`media_tasks`、`speech_tasks` |
| 后续版本（P5） | `effects`、`outbox` 及编排领域表 |
| 后续版本（按能力切片） | `media_tasks`、`speech_tasks` |

P2 需要 `usage_records`（每次提交写用量），因此 V5 随持久化基座落地。V6 已用于修复旧消息投影的类型判别字段；V7/V8 随 P3 落地，分别承载审批/输出投影与会话策略覆盖；V9 随 P4 落地记忆索引。后续从 V10 起按实施顺序递增，不能回改已执行迁移或预先占用版本。SQL 文件是已落地表结构的依据，下面为主要领域字段节选。

关键 DDL（节选）：

```sql
CREATE TABLE conversations (
    id               TEXT PRIMARY KEY,
    title            TEXT NOT NULL,
    assistant_id     TEXT,
    current_node_id  TEXT,
    session_id       TEXT,
    status           TEXT NOT NULL,          -- ACTIVE / ARCHIVED（DELETE 只做归档）
    metadata_version INTEGER NOT NULL DEFAULT 0,  -- 标题/assistant/归档等用户编辑
    last_seq         INTEGER NOT NULL DEFAULT 0,  -- 执行进度高水位
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    archived_at      TEXT,
    UNIQUE (session_id)
);

CREATE TABLE message_nodes (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    parent_id       TEXT REFERENCES message_nodes(id),
    session_id      TEXT NOT NULL,
    anchor_seq       INTEGER NOT NULL,       -- 节点对应的首个事件 seq
    end_seq          INTEGER,                -- 最后一个贡献事件；流式中为空
    role            TEXT NOT NULL,
    status          TEXT NOT NULL,           -- PENDING / STREAMING / COMPLETE / INTERRUPTED / ERROR
    created_at      TEXT NOT NULL,
    UNIQUE (session_id, anchor_seq)
);

CREATE TABLE messages (
    node_id         TEXT PRIMARY KEY REFERENCES message_nodes(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL,
    content_json    TEXT NOT NULL,
    tool_calls_json TEXT,
    tool_call_id    TEXT,
    usage_json      TEXT,
    error_json      TEXT,
    updated_at      TEXT NOT NULL
);

CREATE TABLE inbox_items (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    conversation_id TEXT,                   -- 非 chat session 无产品关联
    target          TEXT NOT NULL,          -- NEXT_TURN / NEXT_STEP
    payload_json    TEXT NOT NULL,
    status          TEXT NOT NULL,          -- PENDING / IN_PROGRESS / CONSUMED / CANCELLED
    idempotency_key TEXT,
    claimed_at      TEXT,
    lease_until     TEXT,
    claim_token     TEXT,
    attempts        INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    consumed_at     TEXT,
    UNIQUE (session_id, idempotency_key)
);

CREATE TABLE agent_sessions (
    id                    TEXT PRIMARY KEY,
    conversation_id       TEXT,
    parent_session_id     TEXT,
    format_version        INTEGER NOT NULL,
    cwd                   TEXT NOT NULL,
    seeded                INTEGER NOT NULL DEFAULT 0,
    inherited_event_count INTEGER NOT NULL DEFAULT 0,
    origin                TEXT,
    delegation_depth      INTEGER NOT NULL DEFAULT 0,
    agent_preset          TEXT,
    status                TEXT NOT NULL,      -- CREATED / RUNNING / IDLE / INTERRUPTED / ENDED
    config_snapshot_json  TEXT,              -- 首次真实 request/header 前为空
    config_version        INTEGER,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    ended_at              TEXT
);

CREATE TABLE session_events (
    session_id             TEXT NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    seq                    INTEGER NOT NULL,
    event_time             TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    data_json              TEXT NOT NULL,
    surface_op_json        TEXT,              -- {"op":"append"} / {"op":"replace","start":..,"end":..}
    surface_start_seq      INTEGER,
    surface_end_seq        INTEGER,
    source_event_seqs_json TEXT,              -- 权威的被遮蔽/引用事件集合
    ignorable              INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id, seq)
) WITHOUT ROWID;

CREATE TABLE tool_approvals (
    id             TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    turn           INTEGER NOT NULL,
    call_id        TEXT NOT NULL,
    tool_name      TEXT NOT NULL,
    args_digest    TEXT NOT NULL,
    reason         TEXT,
    status         TEXT NOT NULL,   -- PENDING / ALLOWED_ONCE / REJECTED / CANCELLED / UNAVAILABLE
    expires_at     TEXT,
    decided_at     TEXT,
    decision_token TEXT,
    created_at     TEXT NOT NULL,
    UNIQUE (session_id, turn, call_id)
);

CREATE TABLE usage_records (
    id                 TEXT PRIMARY KEY,
    session_id         TEXT NOT NULL,
    seq                INTEGER NOT NULL,
    turn               INTEGER NOT NULL,
    step               INTEGER NOT NULL,
    attempt            INTEGER NOT NULL DEFAULT 1,
    record_index       INTEGER NOT NULL,     -- 同事件多条用量的存储序号，不冒充实际 attempt
    provider           TEXT NOT NULL,
    model              TEXT NOT NULL,
    input_tokens       INTEGER NOT NULL DEFAULT 0,
    output_tokens      INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens  INTEGER NOT NULL DEFAULT 0,
    cache_write_tokens INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_micros        INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL,
    UNIQUE (session_id, seq, record_index)
);

CREATE TABLE idempotency_keys (
    scope           TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash    TEXT NOT NULL,
    status          TEXT NOT NULL,          -- IN_PROGRESS / COMPLETED
    response_status INTEGER,
    response_json   TEXT,
    created_at      TEXT NOT NULL,
    expires_at      TEXT NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE TABLE effects (
    id            TEXT PRIMARY KEY,
    kind          TEXT NOT NULL,
    effect_key    TEXT NOT NULL,
    payload_json  TEXT NOT NULL,
    status        TEXT NOT NULL,              -- PENDING / RUNNING / DONE / FAILED / DEAD
    attempts      INTEGER NOT NULL DEFAULT 0,
    next_run_at   TEXT NOT NULL,
    locked_until  TEXT,
    lease_owner   TEXT,
    last_error    TEXT,
    dead_at       TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    UNIQUE (kind, effect_key)
);
```

事件保留与读取：`session_events` 不逐字存流式 token；P2 当前保存内核的流证据摘要，完整流外置归档随对应能力补齐。JDBC 读取以每页 256 条查询一致快照，在读取事务外构造 `Session`；`loadRange` 遵守当前核心接口返回从 seq=1 起的连续前缀，不能冒充 delta API。提交事务只读 session 进度小行，不调用 `load()` 重建全量历史。原始事件默认保留，删除只能走“先导出、后 purge”的流程。

P2 持久化基座的附加字段：`agent_sessions` 保存 `last_seq/open_turn/current_turn/current_step/turn_start_seq/last_attempt` 与完整 header 元数据；配置字段只从真实 `request/header.metadata.configSnapshot` 更新。`message_nodes` 以 `source_seq/visible/surface_position/hidden_at_seq` 维护消息 surface，保留被替换节点与原分支关系；首条用户消息锚定 turn/start。step/start 建立 STREAMING 占位，正式消息完成投影，失败和取消收敛为 ERROR/INTERRUPTED。`usage_records` 另存 `purpose`，模型用量关联实际消息/尝试事件，并校验可验证的 provider/model；`record_index` 仅用于唯一性。全部 JSON 设合法性检查，存储时间统一为 UTC 九位小数，保证 lease 文本比较有序。

索引建议：

```sql
CREATE INDEX idx_conversations_updated ON conversations(status, updated_at DESC);
CREATE INDEX idx_message_nodes_parent ON message_nodes(conversation_id, parent_id);
CREATE INDEX idx_message_nodes_anchor ON message_nodes(session_id, anchor_seq);
CREATE INDEX idx_messages_conversation ON messages(conversation_id, updated_at DESC);
CREATE INDEX idx_sessions_conversation ON agent_sessions(conversation_id, status);
CREATE INDEX idx_inbox_claim ON inbox_items(session_id, status, created_at);
CREATE INDEX idx_approvals_pending ON tool_approvals(status, created_at);
CREATE INDEX idx_approvals_session ON tool_approvals(session_id, status);
CREATE INDEX idx_usage_session ON usage_records(session_id, created_at);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys(expires_at);
CREATE INDEX idx_effects_due ON effects(status, next_run_at);
```

### 7.3 分支、重新生成与 fork

**分支切换（switch / regenerate）**

- `message_nodes` 是树，`conversations.current_node_id` 指向当前叶子；每个节点有 `anchor_seq` / `end_seq`。
- `deriveMessages()` 从事件日志重放，跳过被 `SurfaceReplace` 遮蔽的 seq，再按 surface append 顺序生成消息。被放弃分支的 `assistant/tool` 事件仍在日志里，但不会进入下一次模型请求。
- 重新生成节点 N = 追加 `branch/switch` 控制事件：用 `SurfaceReplace(parent(N).end_seq + 1, maxSeq)` 遮蔽 N 及其之后的事件，把 current 指向 parent(N)，然后在同一 per-session 写者上开启新 turn。新助手节点成为 N 的兄弟；旧分支仍可再次 switch 回来（再追加 replace，遮蔽另一条分支）。
- `branch/switch` 必须是持久事件（log-only，带 `targetNodeId`、`shadowedSeqs`、`fromSeq`、`toSeq`），不能只改投影；`message_nodes` 是它的查询投影。切换/重生成只允许在 idle 状态执行，或先取消当前 turn。
- `messages` 是**每个节点的内容投影**，不是“唯一当前视图”；分支 UI 通过 `message_nodes` + 节点状态查询，模型历史通过 `deriveMessages()` 的 surface 计算。

**Fork**

- Fork 边界 = 一个已闭合 turn 的最后一个 seq；只允许在 idle 会话上 fork，或先由同一写者补 `turn/end {reason: Forked}`。
- 复制前缀事件时保留原 `seq`，连同 `compaction/*` 与 surface 事件一起复制；不复制 `usage_records`（新会话重新计量）。写 `session/end-seed { inherited: true, inheritedEventCount }`，`agent_sessions.inherited_event_count` 记录数量。
- `session/end-seed` 在复制事件之后追加；`turn/end {reason: Forked}` 在源会话中先于复制提交。
- 新 session + 新 conversation + 前缀复制必须由 chat 应用服务调用 `SessionCommitService.fork(...)`，在**同一个事务**中创建；`SessionStore` 不单独创建产品实体，只执行 `commit`。
- 打开中的 turn 不允许直接 fork；`Forked` 只能由 fork 路径合成。

## 8. 关键流程

### 8.1 发送一条消息

```text
POST /api/v1/conversations/{id}/messages
  → SendMessageHandler
      校验 Idempotency-Key、附件；metadata_version 只用于 PATCH 标题/assistant
      单事务：写 inbox_items(PENDING) + idempotency_keys(IN_PROGRESS)
      返回 202 { inputId }              # 不写 message_nodes / messages
  → commit 返回后（连接已归还）：ChatAgentService.submit(sessionId)
  → per-session 串行队列上执行 AgentLoop
      claim inbox（PENDING → IN_PROGRESS，写 lease/claim_token）
      同一事务：turn/start + user/message(inputId) + inbox CONSUMED
                + message_nodes/messages(PENDING → STREAMING)
                + agent_sessions.status = RUNNING
      组装 prompt → 冻结 LlmRequest → request/header + request/context
      LlmAdapter.stream → assistantDelta → SSE（不落库）
      assistant/message（流证据摘要 + usage）
      tool/call（先提交）→ 工具管线 → tool/result
      step/end → 可能下一个 step → turn/end
  → SessionCommitService 提交事件 + 投影 + last_seq + usage + agent_sessions 状态
  → commit 返回后：SSE 入队 durable session.event
```

202 阶段只保证输入已持久化；前端用 `inputId` 做乐观显示。`message_nodes`/`messages` 在认领事务中才出现，`anchor_seq` 就是 `turn/start` 的 seq，因此不存在“没有事件来源的投影行”。

认领事务必须把 `turn/start`、携带 `inputId` 的 `user/message`、`inbox_items.status=CONSUMED`、消息投影和 `agent_sessions.status=RUNNING` 放在同一个 `SessionCommitService` 事务里：要么一起成功，要么一起回滚。崩溃重试时，未消费的 PENDING 输入重新认领；超过 lease 的 IN_PROGRESS 由恢复重置为 PENDING；已提交的输入不会再次生成 `user/message`。`turn/start` 必须在 `user/message` 之前追加。

关键点：

- 输入保存与模型执行分开；返回 202 后前端只订阅 SSE。
- 每轮开始时绑定 `AgentConfigSnapshot`（含 version/hash/frozenAt），运行中不重新读配置。
- SSE 的 durable 事件带 `id = sessionId:seq`；delta 事件是临时的，不占 seq。
- 重连不能“先查库再订阅”，否则两步之间提交的事件会丢。正确顺序：先在 `SseHub` 注册订阅并缓冲实时事件 → 快照高水位 `seq` → 回放 `session_events` → 按 `sessionId:seq` 去重合并。首次连接的 `after` 是 fallback，合法 `Last-Event-ID` 在自动重连时优先于未改变的初始 query。`Last-Event-ID` 必须校验 sessionId 前缀。

### 8.2 工具审批

```text
ToolPipeline.preExecute → ASK
  → 同一提交事务：approval/asked + tool_approvals(PENDING)（UI 才能看到）
  → 在 per-session 写者上 await CompletableFuture + CancellationToken（不持连接）
  → UI POST /api/v1/approvals/{id}
  → HTTP 线程只做 CAS 校验并 resolve future，返回 202
  → 循环恢复后由同一写者追加 approval/decided
  → ALLOWED_ONCE / REJECTED / CANCELLED / UNAVAILABLE
```

- 等待审批期间不持有数据库连接；审批记录本身已经落库。
- HTTP 审批接口不直接追加事件；`approval/decided` 只能由 per-session 写者在恢复后写入，避免与 loop 争 `expectedSeq`。
- 决定是 CAS 幂等操作（`decision_token` / `decided_at`），重复 POST 只结算一次。
- 超时、用户取消、进程重启分别落 `cancelled` / `unavailable`，绝不自动放行；恢复时为未配对的 `approval/asked` 补写 `approval/decided {unavailable}`。
- 审批是单次授权；永久策略写在 assistant/session 配置，并记录配置版本。
- 工具执行结果无论成功失败都写 `tool/result`；拒绝结果作为 `isError=true` 的模型可见内容。

### 8.3 取消

```text
POST /api/v1/conversations/{id}/turns/{turn}/cancel
  → CancelTurnHandler（HTTP 线程）
      校验 turn 归属与当前状态
      Agent.cancel(CancelOptions.keepInbox=true, cause=USER)  # 只发信号
      返回 202
  → per-session 写者被令牌唤醒
      关闭模型流、取消工具 future、结算审批等待
      append turn/end { reason: aborted }（只写一次）
      已交付的部分 assistant 内容以 interrupted=true 结算
      不自动重试，不重放副作用
```

- 取消必须能穿透模型流、工具执行和审批等待。
- 工具若已开始，必须 drain 到可安全停止的位置；取消不能伪造“没执行过”。
- `turn/end` 由写者唯一追加；HTTP 处理器与恢复路径不能各写一次。
- `keepInbox` 默认保留未认领输入；是否重新执行由用户决定。

### 8.4 上下文压缩

```text
step 前 TokenMeter 测量
  → 超过阈值
  → append compaction/start（锁）
  → 选择保留范围（保持 tool call/result 配对）
  → 一次性摘要调用（purpose=compaction）
  → append compaction/summary（记录 shadowed range/seqs/usage）
  → append 替换 user/message（surface replace）
  → append compaction/end
  → 重新测量并继续
```

- 原始事件永不删除；压缩只改变 `deriveMessages()` 看到的 surface。
- 摘要失败保留 attempt 与已产生的部分；不丢原文。
- 未配对的 `compaction/start` 在重启后标记中断，阻塞新的自动压缩，等待用户重试。
- 手动压缩与自动压缩走同一序列；手动入口只允许在 idle 状态。

### 8.5 崩溃恢复

```text
启动
  → InstanceLock（data/.lock）确保单实例
  → Flyway 校验 schema
  → RecoveryService：
      重置 lease 过期的 inbox IN_PROGRESS → PENDING（attempts 超限则 CANCELLED）
      扫描 agent_sessions.status = RUNNING（commit 在 turn/start 时写入）
      对有 turn/start 无 turn/end 的会话：
        补 step/end（若缺失）
        补未完成工具的 tool/result（isError=true, ABORTED）
        补 turn/end { reason: interrupted }
        标记 session INTERRUPTED
      为每个未配对的 approval/asked 追加 approval/decided {unavailable}
      扫描 effects RUNNING 且 lease 过期 → PENDING 或按 attempts DEAD
  → 不自动重发模型请求；不自动重放 Shell/付费调用
```

优雅停机走独立路径：停止接收 → 取消运行中 turn → 等待工具/效果 drain → 由写者补 `turn/end {reason: Shutdown}` → 关库。`Interrupted` 只留给真正的崩溃恢复，不能由停机路径合成。

- `interrupted` 只能由恢复路径合成。
- Worker/Ticket 恢复时给新 Assignment 一条 recovery note，要求先检查工作目录和外部状态，再决定是否重试。
- 恢复顺序：最老的 Ticket 优先；用户可见的待办只保留真正需要人处理的项目。

### 8.6 Ticket 生命周期（第二阶段）

```text
Source → Trigger → Thread → Eval → Decision
  accept → Ticket + Mandate + Assignment（worker-<ticketId>）
  stop / ignore / unavailable → 记录，不创建 Ticket
Assignment:
  pending → running → ended
  report: progress / waiting / ready / blocked / failed / dropped
Ticket:
  open → ended
  人的消息 → resume（同一 Mandate，无新 Eval）
  人的 stop → 撤回 Mandate
```

- `Engine` 是唯一写者；每个命令一个事务；效果先入 `effects`，提交后执行。
- `PassScheduler` 每 2 秒处理到期效果与 Eval。
- `WorkerSlotManager` 限制并发 Worker；人的消息可以使用额外一个 slot。
- Worker 目录在 `data/workspace/tickets/<shortName>`，会话 id 为 `worker-<ticketId>`。
- `Watchdog` 在启动时恢复中断的 Assignment，并报告最老的优先。

## 9. 通信契约

### 9.1 REST

```text
POST   /api/v1/conversations
GET    /api/v1/conversations
GET    /api/v1/conversations/{id}
PATCH  /api/v1/conversations/{id}
DELETE /api/v1/conversations/{id}
POST   /api/v1/conversations/{id}/messages
POST   /api/v1/conversations/{id}/messages/{nodeId}/regenerate
POST   /api/v1/conversations/{id}/branches/{nodeId}/switch
POST   /api/v1/conversations/{id}/compact
POST   /api/v1/conversations/{id}/turns/{turn}/cancel
POST   /api/v1/conversations/{id}/fork
GET    /api/v1/conversations/{id}/messages?branch=...
GET    /api/v1/conversations/{id}/stream?after=<seq>
GET    /api/v1/sessions/{sessionId}/events?after=<seq>
GET    /api/v1/sessions/{sessionId}/export
POST   /api/v1/sessions/{sessionId}/compaction
POST   /api/v1/sessions/{sessionId}/compaction/{seq}/abandon
POST   /api/v1/approvals/{id}
GET    /api/v1/approvals?status=pending
POST   /api/v1/attachments
GET    /api/v1/models
```

- 所有 mutating 接口支持 `Idempotency-Key`，由 `idempotency_keys` 表统一存储响应；同 key 不同 body 返回 409，已完成的请求重放同一响应。
- 发送消息返回 `202 Accepted`，真正的模型结果走 SSE。
- `DELETE /conversations/{id}` 只做软删除/归档（`status=ARCHIVED`）；硬清除走独立的保留期 purge 流程，且必须先导出事件日志。
- 错误统一 `application/problem+json`，包含 `code`、`traceId`、`details`，错误码在 `contracts/errors/codes.yaml` 维护。

### 9.2 SSE

```text
event: session.committed        # durable，id = sessionId:seq
data: { "sessionId": "...", "seq": 42, "events": [...] }

event: assistant.delta          # 临时，不落库
data: { "turn": 1, "step": 1, "block": 0, "text": "..." }

event: assistant.reasoning
event: tool.call
event: tool.result
event: approval.request
data: { "id": "...", "toolName": "shell", "callId": "...", "reason": "..." }

event: approval.resolved
event: usage
event: turn.ended
event: error
event: heartbeat                 # 每 15 秒
```

- **只有 `session.committed` 是 durable 事件**，带 `id = sessionId:seq`；`assistant.delta`、`tool.call`、`tool.result`、`usage`、`turn.ended` 都是同一批已提交事件的临时投影，不带 `id`，客户端不得据此做持久状态。这样不会出现“同一个事件既在 committed 里又在单独事件里”的重复处理。
- 回放不能在查询后才订阅：先在 `SseHub` 注册订阅并缓冲，再快照高水位，再回放 `seq > after`，最后按 `sessionId:seq` 去重合并。首次连接的 `after` 是 fallback，合法 `Last-Event-ID` 在自动重连时优先于未改变的初始 query。`Last-Event-ID` 必须校验 sessionId 前缀。
- 每个连接有有界队列；delta 可合并/丢弃，`session.committed` 不能丢；队列满时关闭连接让客户端重连回放。`SseEmitter` 的 send 由每连接单线程队列串行化，heartbeat 与业务事件不能并发调用 send。
- 配置 `spring.mvc.async.request-timeout`（或 `SseEmitter` 超时）大于 heartbeat 间隔；15 秒心跳不能阻止 Tomcat 默认 30 秒 async timeout。
- 浏览器原生 `EventSource` 不能设置 `X-RikkaHub-Token`。SSE 认证使用同源 HttpOnly cookie，或短时 query token + 严格 Origin 校验；不要要求自定义头。
- 审批和错误是“需要人注意”的事件，必须可靠送达；如果连接断开，客户端重连后通过 `GET /approvals?status=pending` 补齐，错误从 `turn/end` 或 `GET /sessions/{id}/events` 重建。

### 9.3 contracts 目录

```text
contracts/
├── http/openapi.yaml              所有 REST 路径与 DTO
├── sse/events.yaml                AsyncAPI 或 JSON Schema；SSE 事件目录
├── errors/codes.yaml              稳定错误码与 ProblemDetail 扩展
├── plugins/plugin-profile.schema.json
├── websocket/terminal.yaml        第二阶段：终端/审批实时通道
└── generated/                     生成的 TypeScript 类型（提交或构建生成）
```

`contracts/http/openapi.yaml` 已列入首批 P2 端点及 DTO；SSE 与错误码目录同步实际行为。当前手工维护契约，不提前引入代码生成器，后续生成工具必须保持实现与契约一致。

## 10. 配置、提示词、技能与凭据

### 10.1 配置分层

```text
classpath:application.properties          应用默认值
config/defaults/*.yaml                    可提交的公共默认值
config/application.local.properties       本地私有覆盖（已忽略）
config/agents/*.yaml                      Assistant/Agent 预设
config/plugin-profiles/*.yaml             插件组合与参数
环境变量                                  密钥、端口、数据目录
```

- 优先级：环境变量 > 本地私有配置 > profile > defaults。
- 每轮开始时生成 `AgentConfigSnapshot`（含 `version`/`hash`/`frozenAt`）；**每轮权威副本写入 `request/header`**，`agent_sessions.config_snapshot_json` 只保存最近一份，便于恢复与 UI 展示。
- 审批策略解析顺序：会话 `approval/policy` 覆盖 > assistant 配置 > 全局默认；解析结果冻结进本轮快照，运行中不再读取配置。
- 配置变更不修改正在运行的 turn；下一轮生效。
- `config/defaults` 与 `data/config` 的职责保持现有文档约定：模板 vs 本地私有运行配置。

### 10.2 提示词

```text
prompts/
├── system/00-identity.md
├── system/10-environment.md
├── system/20-tools.md
├── system/30-skills.md
├── system/40-memory.md
├── system/90-guardrails.md
├── compression/summary.md
├── title/*.md
├── memory/extract.md
└── translation/*.md
```

- 提示词是数据，不是代码；用简单占位符渲染，禁用 SpEL/脚本。
- `PromptSource` 端口由 backend 实现；`agent-core` 只接收渲染后的 `PromptSection`。
- 内置提示词只读；用户自定义提示词后续放 `data/prompts` 或数据库，并记录版本。

### 10.3 技能

```text
skills/
├── manifest.json
└── builtin/<name>/SKILL.md + 资源文件
data/skills/<name>/...       用户安装/编辑
```

- `SKILL.md` frontmatter：`name`、`description`、`when-to-use`、`allowed-tools`、`version`。
- 采用渐进披露：系统提示词只放 name + description；模型通过 `skill` 工具按需加载正文。
- `SkillCatalog` 由 backend 加载，`agent-core` 只依赖 `SkillSource` 端口。
- 技能正文是不可信内容，不能覆盖系统提示词或审批策略。

### 10.4 凭据

- Provider 只存 `credential_ref`，不存明文 key。
- v1：环境变量或 `config/application.local.properties`；后续接 OS keychain 或本地 AES-GCM 加密文件。
- 日志、错误响应、事件日志都不得出现密钥；API 返回掩码。
- 备份默认不包含凭据；恢复后需要重新配置。

## 11. 并发、事务与幂等

| 问题 | 方案 |
| --- | --- |
| 同一会话并发写 | `SessionTaskQueue` 串行化**所有**写命令（发送/取消/审批/分支/fork/压缩）；HTTP 只 signal |
| 不同会话并行 | 共享有界池 + per-key 链式 future；不要“一个 session 一个线程”，避免空闲会话占线程 |
| 审批等待 | step 挂起，不占 loop 线程；用 future + 专用 waiter，工具池与 loop 池分离 |
| 工具执行 | 独立有界池；`concurrencySafe` 才并行，其余顺序屏障 |
| 模型流 | HttpClient 异步发起；P2 由 adapter 专用有界 I/O 池处理阻塞 Reader（每适配器最多 16 条），不占 HTTP/命令队列线程；不是全程非阻塞 |
| SQLite 单连接 | 只用于短事务；不在流式/审批/Shell/媒体期间持有 |
| 事件顺序 | store 分配连续 `seq`；`expectedSeq` 冲突返回 409；单写者保证 |
| 会话版本 | 拆成 `metadata_version`（用户编辑）与 `last_seq`（执行进度），不再共用一个 `version` |
| 重复提交 | `idempotency_keys(scope,key)` 存请求 hash 与响应；同 key 不同 body 返回 409 |
| 恢复重放 | 只补 `interrupted`；配对未决审批；重置过期 inbox/effect lease；副作用工具声明 `idempotent` 才自动重试 |
| 效果执行 | `effects` 带 lease/attempts/backoff/dead；`kind+key` 唯一，合并同一等待任务 |
| 进程单实例 | `data/.lock` 文件锁；第二个实例启动失败并给出明确错误 |
| 提交后动作 | `@Transactional` 提交方法返回后发布；同步回调只入队，禁止在回调里查库/阻塞/inline |
| 优雅停机 | 停止接新请求 → 取消运行中 turn → 等待工具/效果 → 写 `turn/end {Shutdown}` → 关库 |

执行器规格建议：`turnPool`（交互 turn，大小 = CPU 核数，可配）、`toolPool`（独立，默认 8）、`effectPool`（默认 2）；模型流用异步 HTTP 发起，P2 的阻塞解析使用适配器自有、最多 16 路的有界 I/O 池（额外请求返回可重试 overloaded），不借用产品命令池；三者队列都有界，拒绝时返回 429/503，空闲会话的串行队列在 TTL 后淘汰；审批等待只挂起 step，不占 `turnPool` 线程；停机时按“停止接单 → 取消 → drain → 关库”的顺序，并设置总超时。

本地安全基线（v1 就要有，不要等到“以后加认证”）：

- 在 `application.properties` 明确 `server.address=127.0.0.1`，只监听本机；发布模式由前端同源提供静态资源。
- `StoragePaths` 必须创建 `data/config` 与 `data/logs`；本地令牌写到 `data/config/local-token`，文件权限仅当前用户可读。
- 状态变更接口要求本地令牌；令牌通过同源 HTML 注入或 SameSite=Strict HttpOnly cookie 下发，**不要**假设浏览器能读磁盘文件。SSE 用 cookie 或短时 query token，不使用自定义头。
- 增加 `OriginGuard` 与 Host allowlist，防止 DNS rebinding；开发期 CORS 只允许 `http://localhost:5173`，生产同源，不开通配。
- 上传文件做大小、MIME（magic bytes）、路径校验；工具路径必须 realpath 后落在工作区根内，拒绝符号链接逃逸。
- `WebFetch`/搜索出口做 SSRF 策略：拒绝 private/link-local/loopback/metadata 地址，限制重定向并做 DNS pinning。
- 密钥只存环境变量/本地私有配置/加密文件；日志、ProblemDetail、事件日志都要脱敏。

## 12. 测试与构建门禁

### 12.1 测试分层

- `agent-core`：纯 JUnit 5，`ScriptedLlmAdapter` 按脚本吐 chunk；`ManualClock`、`SequentialIdGenerator`；事件日志 golden 测试；`deriveMessages()` 的 surface replace 属性测试；取消/审批/压缩的并发测试。
- `plugins`：适配器契约测试 + 录制 fixture；MCP transport 用本地假 server；workspace 用临时目录。
- `backend`：`@SpringBootTest` + `@TempDir` 数据目录；Flyway 迁移测试；`JdbcClient` 仓库测试；SSE 集成测试（`WebTestClient`/真实端口）；审批流；崩溃恢复。
- `tests/compatibility`：历史事件日志的 golden 文件，验证新代码能读旧格式。
- `tests/e2e`：前端 + 后端一条完整消息流。
- `tests/visual`：关键界面截图对照。

### 12.2 构建门禁

- `maven-enforcer-plugin`：
  - `agent-core` 禁止 `org.springframework:*` 与**所有** `com.rikkahub:*`（它不需要任何内部模块；`*plugins*` 只匹配聚合 artifact，匹配不到 `model-providers` 等）；`java.sql` 由 ArchUnit 禁止，enforcer 无法匹配 JDK 包。
  - 每个 plugin 禁止 `org.springframework:*`、`com.rikkahub:backend` 以及其他 `plugins/*` 实现模块（只允许 `agent-core`）。
  - 增加一个负向 fixture/构建测试，验证把 `model-providers` 加到 `agent-core` 会真的失败。
- ArchUnit（backend 测试）：
  - `agent-core` 不得依赖 `org.springframework..`、`java.sql..`、`com.rikkahub.features..`。
  - `plugins` 不得依赖 `com.rikkahub..backend..`。
  - 用 slices 强制 feature 隔离：`slices().matching("com.rikkahub.features.(*)..").should().notDependOnEachOther()`；跨 feature 只允许经过对方 `application..` 或共享端口。
  - 单独规则禁止 `features.<a>..` 依赖 `features.<b>.infrastructure..`。
  - 需要 `archunit-junit5` 依赖，并让 `mvn verify` 在规则失败时失败。
- `maven-dependency-plugin:analyze` 检查未声明/未使用依赖。
- `mvn verify` 必须包含 ArchUnit 与迁移测试；`-DskipTests` 只用于开发重启。

## 13. 落地顺序

### P0 边界与构建（先做，半天到一天）

1. 根 pom 加 `dependencyManagement`（Jackson、JUnit、AssertJ、ArchUnit）与 `maven-enforcer-plugin`、surefire/failsafe、`dependency:analyze`。
2. `agent-core` 加 Jackson + SLF4J；`application.properties` 加 `server.address=127.0.0.1`；`StoragePaths` 创建 `config`/`logs`。
3. 加 ArchUnit 测试骨架 + 负向 fixture；把 `docs/project-structure.md` 的约束变成可执行测试。
4. 建 `agent-core-testkit`（ScriptedLlmAdapter、ManualClock、SequentialIdGenerator、FakeSessionStore/FakeInboxStore）与类型清单（附录 A，或独立 `docs/type-inventory.md`）；先让 API 类型可编译。
5. 把 `idempotency_keys`、`tool_approvals`、`usage_records` 的迁移草案与索引先定稿，避免 P2 再改提交事务。

### P1 内核最小闭环（agent-core）

1. `message`：`Message`、`ContentBlock`、`StreamChunk`、`TokenUsage`。
2. `session`：`SessionEvent`、事件 record、`Session`、`deriveMessages()`、`SurfaceOp`。
3. `model`：`LlmAdapter`、`LlmRequest`、`LlmStream`、`LlmFailure`、`RetryPolicy`。
4. `tool`：`ToolDefinition`、`ToolRegistry`、`ToolPipeline` 骨架。
5. `loop`：`Inbox`、`AgentLoop`、`TurnDriver`、`StepDriver`、Hook 接口。
6. `api`：`Agent`、`AgentRuntime`、`AgentHandle`、`CancelOptions`。
7. 纯内存 `FakeSessionStore` + `ScriptedLlmAdapter`，跑通“输入 → 流式 → 工具 → 结束”。

### P2 持久化与聊天闭环

1. V2–V5 迁移：providers/assistants/idempotency/conversations/message_nodes/messages/inbox/agent_sessions/session_events/usage_records（做审批 UI 时连同 tool_approvals）。
2. `SessionCommitService` + `JdbcSessionStore`：事件 + 投影 + `last_seq` + usage + inbox 状态 + agent_sessions 状态同事务。
3. `chat` 切片的 `api/application/domain/infrastructure/agent`；chat 只实现 `SessionProjection`，不重复实现 `JdbcSessionStore`。
4. `model-providers` 的 OpenAI-compatible 适配器（先支持一个 provider）。
5. SSE Hub、回放、心跳、审批事件；发送/取消/重连跑通。
6. `bootstrap.RecoveryService` + `ChatRecoveryContributor`：补 `interrupted`、配对未决审批、重置过期 inbox lease。

### P3 工具、审批与工作区

1. `builtin-tools`：read/write/edit/glob/grep/shell/terminal。
2. `workspace-runtimes`：本地文件系统、进程、后台任务；v1 终端先用 pipe-based，PTY 作为可选 `pty4j`/ConPTY 适配层；取消时用 `ProcessHandle.descendants()` + 进程组/job object 杀进程树。
3. `approval`：ask/never、单次授权、超时/取消/恢复；`ToolPolicy` 预设与路径/命令规则。
4. 工具并发分类、超时、输出 spill 到 `data/tool_outputs`。
5. 路径逃逸、符号链接、命令注入、环境变量清理、SSRF 的测试。

### P4 上下文、技能、记忆、搜索、MCP

1. `context`：prompt sections、token meter、compaction。
2. `skills` + `prompts`：清单、渐进披露、版本。
3. `memory`：Markdown + FTS5；异步提取。
4. `search`、`mcp`、`translation`、`speech`、`media` 按需接入。
5. `usage` 聚合与 UI。

### P5 超智能体编排

1. `agent-core/orchestration` 领域模型 + `Engine` 五部分。
2. `effects`/`outbox` + `PassScheduler` + `Watchdog`。
3. `Ticket`/`Assignment`/Worker session + `data/workspace/tickets`。
4. 可选 `Source`：定时、Webhook、GitHub/Feishu。
5. `Eval` 双层判定与 `Mandate` 授权；默认 `observe`，不自动接单。

## 14. 决策记录与待确认问题

### 已建议的决策

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| ADR-001 | 编译期插件，不做动态类加载 | 依赖边界、可测试性、部署简单 |
| ADR-002 | 事件日志存 SQLite，JSONL 只做导出 | 已有 SQLite/Flyway 选型；单文件备份 |
| ADR-003 | 单 JVM、单 SQLite 写连接、每会话单写者 | 本地单用户产品的最简可靠模型 |
| ADR-004 | `agent-core` 只加 Jackson/SLF4J | 事件 JSON 是内核职责；避免 Spring 渗入 |
| ADR-005 | 聊天用 SSE，终端/审批可用 WebSocket | SSE 足够、易回放；终端需要双向 |
| ADR-006 | 不用 Spring AI/LangChain4j | 避免与自研 harness 的循环/审批语义冲突 |
| ADR-007 | 编排层第二阶段，v1 不做 Eval 自动接单 | 控制复杂度；先跑通单会话闭环 |
| ADR-008 | 分支切换用 `branch/switch` + `SurfaceReplace` 遮蔽，不复制/删除事件 | 保持单一 append-only 日志，同时支持 regenerate/切换 |
| ADR-009 | inbox 使用 IN_PROGRESS + lease + claim_token，消费与 `turn/start` 同事务 | 崩溃不丢输入、不重复计费 |
| ADR-010 | `metadata_version` 与 `last_seq` 分离 | 用户编辑并发和执行进度不能共用一个计数器 |

### 需要产品确认

1. 目标是纯本地单用户，还是未来要多用户/远程访问？多用户会影响 `tenant_id`、认证、SQLite 并发和 Worker 隔离。
2. 是否需要“主动型 Agent”（定时/Webhook 自动接单）？如果 v1 不需要，编排层可以只做“用户显式发起的后台任务”。
3. 工具默认策略是只读、工作区写入、还是完全访问？这决定审批默认值和沙箱实现。
4. 记忆是“用户可编辑的 Markdown”还是“自动抽取的数据库记录”？两者可以并存，但索引与 UI 不同。
5. 首个要支持的模型 provider 是哪家？OpenAI-compatible 适配器可以覆盖大多数，但流式工具调用和 reasoning 字段差异需要逐家验证。
6. Java 17 是否必须？如果允许 Java 21，可以用虚拟线程简化并发，但会改变构建基线。

## 15. 常见错误与边界检查清单

| 错误 | 后果 | 正确做法 |
| --- | --- | --- |
| 把 `Conversation`、`Assistant` 等产品实体传进 `agent-core` | 内核被产品污染，无法独立测试 | 只传 `AgentSpec`、`AgentConfigSnapshot`、`Message`、`ToolDefinition` |
| 在流式 token 回调里逐字写库 | SQLite 单连接被长时间占用，其他会话阻塞 | 内存合并，`assistant/message` 结算时一次提交 |
| Controller 直接调用 `AgentLoop` | 事务、投影、SSE 顺序失控 | Controller → application service → `AgentRuntime` |
| 让 `agent-core` 依赖 Spring 注解/Bean | 内核无法脱离容器测试 | 只用普通构造器与端口；Spring 装配在 backend |
| 把 `messages` 表当成执行真相 | 恢复、分支、审计都会丢信息 | `session_events` 是真相，`messages` 是可重建投影 |
| 审批逻辑散落在每个工具里 | 策略不一致，无法审计 | 统一走 `ToolPipeline` 的 `ask` 分支与 `ApprovalService` |
| 多个 feature 共享 repository 或直接查对方表 | 边界腐化，改一处崩一片 | 通过 application service 或 `SessionProjection` 端口 |
| v1 就上动态插件、多 Agent、Eval 自动接单 | 复杂度吞掉交付 | 先跑通单会话闭环，编排层按 P5 逐步加 |

提交前检查：

- [ ] `agent-core` 的 `mvn dependency:tree` 没有 Spring、JDBC、servlet。
- [ ] 新增的 `plugins/*` 依赖只有 `agent-core`。
- [ ] 新增的跨 feature 调用经过 ArchUnit 规则。
- [ ] 新增的事件类型有对应 record、JSON 往返测试、旧日志兼容策略。
- [ ] 新增的副作用工具有 `idempotent` 声明与恢复策略。
- [ ] 新增的 SSE 事件有 durable/ephemeral 分类与回放行为。
- [ ] 新增的表有 Flyway 迁移、索引、备份影响说明。

## 附录 A：核心类型清单

以下类型在正文中被引用，落地时必须先定义；每个类型只有一个归属包。

| 类型 | 归属 | 作用 |
| --- | --- | --- |
| `AgentSpec` | `agent-core/api` | `AgentRuntime.create` 的输入：session 头、config 快照、初始输入、环境 |
| `AgentContext` | `agent-core/api` | 只读运行时上下文：sessionId、cwd、scope、时钟、logger |
| `AgentOptions` | `agent-core/api` | resume 选项：config、environment、父取消令牌 |
| `CancelOptions` / `CancelResult` / `CancelCause` | `agent-core/api` | 取消原因（USER/TIMEOUT/SHUTDOWN/PARENT）与结果 |
| `SessionHeader` | `agent-core/session` | 新会话元数据：conversationId、cwd、origin、parent、config 版本 |
| `SessionSnapshot` / `SessionSummary` | `agent-core/session` | 会话读取模型 |
| `SessionCommit` / `SessionCommitResult` / `SessionStatusTransition` / `InboxConsumption` / `ForkRequest` | `agent-core/session` | 唯一提交命令、状态迁移与 fork 请求；结果含 lastSeq、metadataVersion |
| `NewSessionEvent` | `agent-core/session` | 未分配 seq/time 的事件 |
| `SessionEvent` | `agent-core/session` | 已分配 seq/time 的持久事件；payload 在 session/event |
| `SurfaceMeta` / `SurfaceOp` / `SurfaceOp.SurfaceReplace` | `agent-core/session` | surface 追加/遮蔽 |
| `EventUpcaster` / `EventCodec` | `agent-core/session` / `backend/platform/storage` | 内核保留格式升级端口；P2 存储 codec 负责日志编解码 |
| `InboxItem` / `PendingInput` / `ClaimedInput` / `ClaimToken` / `InputId` | `agent-core/api` + `agent-core/port` + `agent-core/session` | `InboxItem` 在 api；PendingInput/ClaimedInput 在 port；InputId/ClaimToken 在 session，保证提交模型不反向依赖存储端口 |
| `ContentBlock` / `Message` / `StreamChunk` / `TokenUsage` | `agent-core/message` | 消息、内容块与流式归一化 |
| `LlmRequest` / `LlmStream` / `StreamListener` / `LlmFailure` | `agent-core/model` | 模型请求与流式监听 |
| `ModelRef` / `ModelInfo` / `ProviderInfo` / `GenerationOptions` | `agent-core/model` | 模型选择与目录 |
| `ToolDefinition` / `ToolSchema` / `ToolCall` / `ToolResult` / `ToolRunContext` | `agent-core/tool` | 工具定义与执行 |
| `ToolDecision` / `ToolRule` / `ToolPolicy` / `PermissionPreset` / `ToolScope` | `agent-core/tool` | 策略与守卫 |
| `ApprovalPolicy` / `ApprovalRequest` / `ApprovalOutcome` | `agent-core/approval` | 审批；P1 用 String id，P3 再引 `ApprovalId` |
| `PromptSection` / `PromptProfile` / `SystemPromptUpdatePolicy` | `agent-core/context` | 提示词组装 |
| `SkillId` / `Skill` / `SkillSummary` / `SkillSource` | `agent-core/skill` | 技能（`SkillCatalog` 在 backend） |
| `UsageRecord` / `EffectIntent` | `agent-core/session` | 提交事务携带的用量与效果；让 `SessionCommit` 不反向依赖 `port` |
| `SessionProjection` / `ProjectionContext` | `backend/platform/storage` | 投影端口（chat 实现） |
| `AgentEvent` / `AgentEventSink` | `agent-core/event` | 进程内实时事件 |
| `PluginDescriptor` / `PluginContext` / `PluginContribution` / `PluginCatalog` | `agent-core/plugin` | 编译期插件装配 |
| `Workspace` / `FileSystem` / `ProcessRunner` / `Terminal` / `JobManager` | `agent-core/execution` | 工作区与进程 |

规则：所有 `sealed interface` 的 `permits` 子类型必须在同一包内声明为 `record`/`final class`，并加一个“只编译 API”的测试，防止设计稿长期不可编译。`CommitAck` 由 `SessionCommitResult` 取代，不再保留两套 ack 语义。

## 附录 B：评审修订记录（2026-11-02）

本轮独立对抗性评审确认的 3 个 blocker 已修复：

1. **发送阶段伪造投影**：202 只写 `inbox_items` + `idempotency_keys`；`message_nodes`/`messages` 与 `turn/start` + `user/message` 同事务创建，`anchor_seq` 由存储层分配。
2. **分支与线性事件日志冲突**：新增 `branch/switch` 事件与 `SurfaceReplace` 遮蔽语义；`deriveMessages()` 跳过被遮蔽的 seq；`messages` 明确为“每个节点的内容投影”，不是唯一当前视图。
3. **inbox claim 崩溃丢输入**：新增 IN_PROGRESS、lease、claim_token、attempts；claim/complete 分别在独立事务与提交事务中完成；恢复重置过期 lease。

同时修复的 major：唯一提交路径与 `NewSessionEvent` 序号分配；所有会话写命令单写者化；提交方法返回后发布（同步回调只入队，不查库）；usage 进入 `SessionCommit` 同事务；工具管线 hooks 与“每个 tool/call 恰好一个 tool/result”；surface DDL；fork 边界与事务归属；`agent_sessions.status` 纳入提交；`metadata_version`/`last_seq` 拆分；`idempotency_keys` 表；SSE 回放竞态与 durable/ephemeral 分类；线程池与审批等待；`JdbcSessionStore`/`RecoveryService`/`SkillCatalog` 归属去重；核心类型清单。

历史阶段记录（2026-11-02，早于后续 P1/P2 实施，不代表当前状态）：P0 构建约束已生效；P1 进行中，基础类型、配置和 API 已落地，`port` 仍是待替换的旧存储接口，`loop` 已有输入队列及 step/turn 辅助类，工具注册表与唯一执行管线已落地，`Shutdown` JSON 变体已补齐。本轮严格只修改现有文件，修复编译、白名单、流式终态与工具审批/取消问题；阶段 API 形状及后续收敛项见 `kernel-api-contract.md` 文末。原子提交、inbox lease、`BranchSwitchEvent`、完整运行循环/运行时与永久内核测试尚未完成，不能视为 P1 退出。实现顺序见 `backend-implementation-plan.md`；V2–V9 迁移、SSE/Security/Idempotency、CI 与发行打包仍待按阶段执行。

## 附录：DeepSeek 概念 → Java 位置

| DeepSeek | Java |
| --- | --- |
| `ctx.sessions` / SessionEvent log | `agent-core/session` + `session_events` |
| `deriveMessages()` | `Session.deriveMessages()` + `MessageProjectionService` |
| `ctx.agents` / `Agent` | `agent-core/api.Agent` + `AgentRuntime` |
| `ctx.agentLoop` | `agent-core/loop.AgentLoop` |
| `Inbox` / `followup` / `steer` / `inject` | `Inbox` + `Agent` 方法 |
| `ctx.llm` / `LlmAdapter` / `StreamChunk` | `model` + `model-providers` |
| `ctx.tools` / guarded pipeline | `tool.ToolPipeline` + `ToolGuard` |
| `approval/asked` / `approval/decided` | `approval` + `tool_approvals` |
| `ctx.systemPrompt` | `context.SystemPromptAssembler` + `prompts/` |
| `ctx.tokenMeter` / compaction | `context.TokenMeter` / `Compactor` |
| `ctx.subagents` | `subagent` + `orchestration` |
| `ctx.skills` | `skill` + `skills/` |
| `ctx.jobs` | `execution.JobManager` |
| `ctx.workspaceRegistry` / `ctx.fs` | `execution.Workspace` / `FileSystem` |
| `ctx.sandbox` | `execution.SandboxPolicy` + `workspace-runtimes` |
| `SessionPersistence` | `port.SessionStore` + `JdbcSessionStore` |
| `ctx.sessionProjections` | `session.SessionProjection` + backend 查询服务 |
| Super-agent `Trigger/Thread/Eval/Decision/Ticket/Mandate/Assignment` | `agent-core/orchestration` + `features/orchestration` |
| Super-agent `Engine` 五部分 | `Engine` + `Intake/Evals/Tickets/Assignments/Replies` |
| Super-agent `effects` / `outbox` | `effects` / `outbox` 表 + after-commit 派发 |
| Super-agent `Watchdog` | `RecoveryService` + `InstanceLock` |
| Super-agent `WorkerSlots` | `WorkerSlotManager`（第二阶段） |

## 参考

- DeepSeek Harness：<https://github.com/deepseek-ai/deepseek-harness>
- Harness 架构：<https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md>
- Harness 子系统参考：<https://deepseek-harness.github.io/deepseek-harness/reference/subsystems/core>
  - session、persistence、tools、approval、system-prompt、compaction、subagent、skills、storage、workspace、sandbox、llm-streaming、token-meter、spill、jobs
- dsh-superagent-prototype：<https://github.com/deepseek-ai/dsh-superagent-prototype>
  - 领域模型：<https://raw.githubusercontent.com/deepseek-ai/dsh-superagent-prototype/main/docs/001-domain.md>
  - 授权：<https://raw.githubusercontent.com/deepseek-ai/dsh-superagent-prototype/main/docs/002-authorization.md>
  - 执行：<https://raw.githubusercontent.com/deepseek-ai/dsh-superagent-prototype/main/docs/003-execution.md>
  - 架构：<https://raw.githubusercontent.com/deepseek-ai/dsh-superagent-prototype/main/docs/007-architecture.md>
- Cordis（Harness 的插件基础）：<https://github.com/cordiverse/cordis>
- 本项目现有约束：`docs/project-structure.md`、`docs/database.md`、`docs/development.md`

上游为 TypeScript 实现，本文只做概念映射，不复制代码。落地前请把 `references/sources.json` 中的 revision 与 license 从 `pending-verification` 更新为实际值。
