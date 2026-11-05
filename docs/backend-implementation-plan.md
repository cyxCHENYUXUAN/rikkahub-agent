# 后端实施计划

> 状态：P0–P5 已完成；阶段验收与清理记录见各节（2026-11-04）
> 依据：[backend-architecture.md](backend-architecture.md)
> 配套：[kernel-api-contract.md](kernel-api-contract.md)、[project-structure.md](project-structure.md)、[database.md](database.md)

本文件把架构文档的 P0–P5 拆成可执行任务，每个任务都有明确产出、依赖与验收标准。`backend-architecture.md` 是设计与理由的权威来源；本文件只负责排序、拆分和验收。三者冲突时先改架构文档，再改契约，最后改本计划。

## 0. 执行原则

1. **先立边界，再写功能**。P0 不通过，不进入 P1；边界用构建失败表达，而不是靠约定。
2. **内核先于产品**。`agent-core` 的会话事件、模型流、工具管线必须先有纯内存闭环与测试，backend 才接数据库和 HTTP。
3. **单写者、短事务**。事件追加、投影、版本、用量、效果在同一个 `SessionCommitService` 事务里提交；提交后才广播 SSE。
4. **不提前建表、不提前生成代码**。表结构在对应切片的数据模型确定时加迁移；OpenAPI 在第一个端点实现时补。
5. **每完成一个里程碑跑一次 `mvn verify`**。`-DskipTests` 只用于开发期重启，不作为验收。
6. **本计划中的假设**（v1 默认值，可在产品确认后调整）：
   - 纯本地单用户；默认只监听 `127.0.0.1`。
   - Java 17 语言/字节码基线；当前 JDK 22 仅作为构建运行时。
   - 第一个 provider 为 OpenAI-compatible 协议。
   - 工具默认策略为“工作区内读写 + 危险操作审批”；审批结果只允许单次放行。
   - 记忆使用用户可编辑 Markdown + FTS5 索引。
   - v1 不做主动型 Agent；orchestration 只在 P5 落地。

## 1. 里程碑总览

| 里程碑 | 目标 | 退出标准 | 状态 |
| --- | --- | --- | --- |
| P0 | 构建边界与可执行约束 | `mvn verify` 通过；enforcer 能拦截非法依赖；ArchUnit 规则生效；空规则不再“假通过” | 已完成 |
| P1 | `agent-core` 最小闭环 | 内存后端跑通“输入 → 流式 → 工具调用 → 结果 → turn/end”；临时案例验证事件、提交、lease、取消与并发退出 | 已完成 |
| P2 | 持久化与聊天闭环 | V2–V6 迁移；`SessionCommitService` 同事务提交；发送/取消/SSE 重连端到端 | 已完成（2026-11-04：本地协议与可执行 JAR 验收通过；真实外部模型 HTTP→SSE 验收通过） |
| P3 | 工具、审批与工作区 | 内置工具 + 本地执行环境 + 审批 + 崩溃恢复 | 已完成（2026-11-04：83 项功能/架构验收、14 项可执行 JAR 检查、独立审查修复、临时测试清理、清理后 4 项架构门禁） |
| P4 | 上下文、技能、记忆、搜索、MCP | 提示词装配、压缩、技能渐进披露、记忆检索、搜索/MCP 接入 | 已完成（2026-11-04：上下文/压缩、技能、记忆、用量、搜索与 MCP 端口/适配器及阶段验收） |
| P5 | 超智能体编排 | Ticket/Worker/effects/outbox/watchdog 与人工待办面板 | 已完成（2026-11-04：领域/队列/Worker/建议评估/恢复/工作台；33 项模块验收与 20 项浏览器检查通过） |
| R1 | 质量与回归网 (自动化 Testkit) | 交付 gent-core-testkit 模块（ScriptedLlmAdapter、FakeExecutionEnvironment、TestFixtures）；无代币覆盖流式、推理、取消、工具与编排 | 已完成（2026-11-04：5 项测试通过，清理后全模块 clean verify 通过 4 项架构门禁，产物指纹固化） |
| R2 | 本地单机发行闭环 (一体化打包/桌面) | 组装前后端静态资源内嵌、一键启动入口、运行依赖检测、独立数据目录、升级恢复处理 | 已完成（2026-11-04：静态资源内嵌、SPA 回退、独立数据目录、桌面启动脚本、Fat JAR 组装与 5 项验收通过，清理后全模块 clean verify 4 项架构门禁通过） |
| R3 | 完整备份与容灾恢复 | 基于一致性快照协调全量归档（DB+Memory+Outputs），脱敏校验，隔离恢复 | 已完成（2026-11-04：快照归档服务、Zip-Slip防御、在线SQLite热恢复、Watchdog收敛、REST API与5项验收通过，清理后全模块 clean verify 4项架构门禁通过） |
| R4 | 生产级沙箱与 PTY 终端 | Windows ConPTY / Unix pseudo-terminal；可选容器隔离（Docker/Podman/WSL） | 已完成（2026-11-04：ConPTY原生适配、自适应PIPE回退、SandboxDetector探测降级、容器参数沙箱隔离、REST能力探针与5项验收通过，清理后全模块 clean verify 4项门禁通过） |
| R5 | 协议与生态扩展 | MCP 2025-11-25 Stdio 本地子进程客户端；多厂商原生模型协议（Claude/Gemini）流式与 Reasoning 结构化 | 已完成（2026-11-04：StdioMcpClient 子进程管道与握手、Claude 原生流与 Thinking 提取、Gemini 原生流与 FunctionCall 归一化、Provider 统一多厂商注册分发与 5 项验收通过，清理后 clean verify 4 项门禁通过） |
| R6 | 协同与外部通道扩展 | 外部 Trigger 适配器（GitHub/飞书/Slack 验签）；Outbox 异步向外部投递；Subagent 协同网络 | 已完成（2026-11-04：外部 Webhook 加密签名防伪与防重放、默认 Observe 外部输入隔离、Outbox 异步可靠投递状态收敛、Subagent 独立会话与工作区委派及 5 项验收通过，清理后全模块 clean verify 4 项门禁通过） |

## 2. P0：构建边界与可执行约束

| 任务 | 内容 | 产出 | 验收 |
| --- | --- | --- | --- |
| P0-1 | 根 pom 统一版本管理 | Spring Boot BOM 管理 Jackson/JUnit/AssertJ，`dependencyManagement` 显式管理 ArchUnit；`maven-enforcer-plugin` 环境规则 | `mvn help:effective-pom` 可见统一版本 |
| P0-2 | `agent-core` 只加 Jackson + SLF4J | `agent-core/pom.xml` 依赖与测试依赖 | `mvn dependency:tree -pl agent-core` 无 Spring/JDBC/Servlet |
| P0-3 | 依赖方向由构建强制 | `agent-core` 与 `plugins/*` 的 `bannedDependencies` | 故意加 Spring 依赖时 `mvn verify` 失败 |
| P0-4 | ArchUnit 边界测试 | `backend/src/test/java/com/rikkahub/architecture/ArchitectureRulesTest.java` | 规则对现有代码通过；跨 feature infrastructure 规则有失败样例验证 |
| P0-5 | 依赖分析报告 | `maven-dependency-plugin:analyze-only`（verify 阶段，先报告不阻断） | `mvn verify` 输出未声明/未使用依赖 |
| P0-6 | 文档与约束一致 | 本文件、`project-structure.md`、`development.md` 与生效规则一致 | 新人按文档能复现边界检查 |

P0 完成定义：`mvn verify -DskipTests` 成功；`mvn verify` 成功；enforcer/ArchUnit 规则不是空跑。

当前状态：已完成。根 pom 环境 enforcer、`agent-core`/`plugins` 的 `bannedDependencies`、ArchUnit 边界测试及负向 fixture、`agent-core` 的 `dependency:analyze` 均已生效。

## 3. P1：`agent-core` 最小闭环

实现顺序严格按层，后层依赖前层：

| 层 | 任务 | 包 | 关键产出 |
| --- | --- | --- | --- |
| L0 | P1-1 消息与基础类型 | `message`、`scope`、`approval`、`api`（取消） | `ContentBlock`、`Message`、`StreamChunk`、`TokenUsage`、`AgentScope`、`CancellationToken` |
| L1 | P1-2 会话事件与投影 | `session`、`session.event` | 20 类事件（含 `branch/switch`）、`SurfaceOp`、`Session.deriveMessages()`、格式版本 |
| L1 | P1-3 模型契约 | `model` | `LlmAdapter`、`LlmRequest`（不可变）、`LlmStream`、`RetryPolicy`、`LlmFailure` |
| L1 | P1-4 工具与执行契约 | `tool`、`execution` | `ToolDefinition`、`ToolRegistry`、`ToolPipeline`、`ExecutionEnvironment` |
| L1 | P1-5 进程内事件 | `event` | `AgentEvent`、`AgentEventSink`、`AgentEventListener` |
| L2 | P1-6 提交、端口与配置 | `session`、`port`、`config` | `NewSessionEvent`、`SessionCommit`、`SessionStore.commit`、inbox lease、冻结配置 |
| L2 | P1-7 对外契约 | `api` | `Agent`、`AgentRuntime`、`AgentHandle`、`AgentSpec`、`CancelOptions` |
| L3 | P1-8 循环 | `loop`、`api` | `RuntimeServices`、`AgentLoop`、`LoopAgent`、`TurnDriver`、`StepDriver`、Hook 链 |
| L4 | P1-9 运行时与内存后端 | `loop` | `DefaultAgentRuntime`、`InMemoryAgentStore`；后者同一实例实现 session/inbox 两个端口 |
| L4 | P1-10 内核验收 | 临时 JUnit 案例 | 事件 JSON、surface、提交原子性、lease、工具审批/超时/drain、端到端与并发退出；验收后按用户要求删除 |
| L4 | P1-11 评审修订收敛 | `session`、`model`、`tool`、`api`、`loop` | 类型归属、模型深冻结、取消监听竞态、重试/取消用量、唤醒与关闭死锁 |

当前状态（2026-11-02）：P1 功能闭环已完成，范围如下：

- `session.event` 的 20 类 payload（含 `branch/switch`）、8 类 turn 结束原因、surface append/replace 与派生消息已实现。
- `SessionStore.commit` 是创建、追加、fork 的唯一写入口。`InMemoryAgentStore` 用同一把锁提交事件、状态 CAS、用量、效果和输入消费；序号/时间由存储分配。fork 校验真实父前缀与已闭合 turn 边界。
- inbox 支持按 target 认领、租约到期回收、过期 token 拒绝消费和 retryable release。输入消费与可见 `user/message`、turn 状态同一提交。
- `ToolPipeline` 接口及 `DefaultToolPipeline` 已实现守卫、合并审批、内容投影、post/finalize 和结构化错误。`awaitQuiescence` 按 session 等待实际任务退出；宿主 executor 由宿主管理。非协作式任务返回错误后仍被追踪，不能假定已停止。
- `RuntimeServices`、`AgentLoop`、`LoopAgent` 和 `DefaultAgentRuntime` 完成输入、模型流、工具、重试、取消和 turn 结束流程。支持 `initialInputs`、每会话单写者、取消后新输入唤醒、关闭时 drain、idle 回调关闭，以及拒绝直接恢复打开中的 turn。
- 模型请求、嵌套选项/元数据和工具 schema 深冻结；重试复用同一请求。失败尝试及取消时已发生的 usage 进入提交，避免漏记或重复计量；取消监听注册与触发竞态已处理。

最终保留的配置契约为 `AgentConfigSnapshot(long version, String hash, Instant frozenAt, ...)`，提供 `freeze(...)`，兼容旧十参构造器与 `fingerprint()`；`skills` 仍为 `List<String>`。`InputId`、`ClaimToken`、`UsageRecord` 位于 `session`，`UsageRecord.sessionId` 为 `SessionId`；`PendingInput`、`ClaimedInput` 位于 `port`。

2026-11-02 验收：全模块 `mvn clean verify` 通过，76 项临时内核测试与 4 项原有架构检查全部成功。按用户要求，P1 临时测试和案例在验收后全部删除，保留原 P0 架构测试及 fixture；没有交付永久 `agent-core-testkit`。清理后再次执行 `mvn clean verify` 成功，4 项保留架构检查通过，确认没有临时测试源码或编译案例残留。此后的 `mvn -pl agent-core test` 不会重新运行这些内核案例；后续需要复现时须重新准备相应测试。

本次重新建立 26 项 P1 行为测试及 2 项存储故障回归。复核发现并修复了退出阶段读取失败导致 idle/close 挂起，以及工具结果提交失败却错误关闭 turn 的问题。存储/工具 drain 故障现在异常终结 future，未确认排空时保留写者；工具结果写入失败保留打开的 turn，拒绝继续执行，不能通过重跑工具补偿。

本次 P1 验收与架构门禁通过后才进入 P2。以下首切片记录当时的 JDBC 基座状态；当时尚未实现的 HTTP/SSE、provider 与恢复已在 2026-11-03 聊天切片补齐，当前状态以 §4 后续记录为准。本地进程/PTY、上下文与编排仍属后续任务。

## 4. P2：持久化与聊天闭环

### 本轮执行切片：P2-1～P2-5 持久化基座

先重新执行 P1 行为验收并修复阻断项，再进入以下任务；本轮不把 HTTP/SSE、真实 provider 或崩溃恢复提前标为完成。

- [x] P1 重新验收：临时 `P1AcceptanceTest` 和存储异常回归覆盖事件、surface、提交、lease、模型/工具闭环、取消及退出；故障不能把未配对工具历史关闭为可继续的 turn。
- [x] 迁移：新增 V2～V5；V1 保持原样。V2 定义 provider/assistant 与幂等键；V3 定义产品消息及 inbox；V4 定义 session/event；V5 本切片仅落用量，审批和文件按实际功能另增迁移。
- [x] 公共存储组件：`platform/storage/EventCodec`、`TransactionRunner`、`SessionProjection`、`ProjectionContext`；JSON 显式判别，时间统一固定 UTC 精度，事务只重试 SQLite BUSY。
- [x] JDBC 端口：`JdbcSessionStore` 委托唯一 `SessionCommitService`，`JdbcInboxStore` 实现独立短事务 claim/release 和提交内部 complete。事件、seq/status CAS、输入消费、配置快照、usage 与投影同事务。
- [x] 消息投影：`features/chat/infrastructure/persistence/MessageProjectionService` 实现平台端口；用户节点锚定 turn/start，消息分支保留，执行推进不递增 metadata_version。
- [x] 临时 SQLite 集成验收：迁移约束、重启可读、乐观冲突、过期 token、消费原子性、投影失败回滚、配置快照、用量与 fork；仅使用临时目录数据库。
- [x] 全模块 `mvn clean verify`；更新阶段记录，删除本轮新增测试和临时案例后，再运行干净构建与原有架构门禁。

本切片的存储约定：`SessionHeader` 不携带初始配置，因此 session 的配置字段在首次真实 `request/header` 之前允许空值；非 chat session 的 conversation 关联可空。`agent_sessions.last_seq` 和 turn/step 状态支持事务内增量校验，不在提交时加载全量事件。用量保留 purpose 和每次提交的记录序号，不能把技术序号冒充实际模型 attempt。effects/outbox 属于后续效果里程碑，本切片对非空 effects 提交整体拒绝，不能静默丢弃。

本次完整验收：`mvn clean verify` 成功，28 项 P1 复核、17 项 P2 SQLite/实际 Spring 装配/内核接入测试、4 项原有架构检查，共 49 项全部通过。JDBC 版内核实际跑通工具调用后再回复；模型、工具和提交后通知期间没有占用连接或事务。没有发起真实付费模型请求，也没有以 HTTP/SSE 验收替代项冒充 P2 整体完成。

按用户要求，已删除本次新增的 `P1AcceptanceTest`、`P1ReviewRegressionTest`、`PersistenceAcceptanceTest` 及临时案例/日志；清理后再次执行全模块 `mvn clean verify` 成功，4 项原有架构检查通过，未发现测试源码或 class 残留。后续 `mvn verify` 不会重新执行已删除的 45 项功能案例。

测试先写后实现，每个切片先观察缺失行为或缺陷的失败，再验证修复；当前目录不含 Git 元数据，不执行提交或分支操作。

| 任务 | 内容 | 产出 | 依赖 |
| --- | --- | --- | --- |
| P2-1 | V2 迁移 | `providers`、`assistants`、`idempotency_keys` | P1 |
| P2-2 | V3–V4 迁移 | `conversations`、`message_nodes`、`messages`、`inbox_items`（含 lease/claim_token/attempts）、`agent_sessions`、`session_events` | P2-1 |
| P2-3 | V5 迁移（用量先落地） | `usage_records`；审批/文件 API 确定后另增迁移，不回改 V5 | P2-2 |
| P2-4 | 提交服务 | `SessionCommitService`、`TransactionRunner`、`SessionProjection` | P2-3 |
| P2-5 | JDBC 存储 | `JdbcSessionStore`（唯一 `commit`）、`JdbcInboxStore`、事件 codec | P2-4 |
| P2-6 | chat 切片 | `api/application/domain/infrastructure/agent` | P2-5 |
| P2-7 | OpenAI-compatible 适配器 | `OpenAiCompatibleAdapter`、`StreamAssembler`、`RetryClassifier` | P1 |
| P2-8 | SSE | `SseHub`、回放、心跳、背压、断线补齐 | P2-6 |
| P2-9 | 恢复 | `InstanceLock`、`RecoveryService` + `ChatRecoveryContributor`：补 `interrupted`、配对未决审批、重置过期 inbox lease | P2-5 |
| P2-10 | 本地安全基线 | 127.0.0.1、CORS、`X-RikkaHub-Token`、上传校验 | P2-8 |

P2 退出标准：一条真实模型消息可从 HTTP 202 到 SSE 完成；进程崩溃重启后未完成 turn 被标记 `interrupted`，不重放付费调用；审批等待期间不占数据库连接。

当前任务状态（2026-11-03）：P2-1～P2-10 当前文本聊天切片的生产实现已落地，包括产品端点、真实 OpenAI-compatible 协议适配器、SSE、启动恢复和本地浏览器安全链路。HTTP → 202 → 本地协议服务器 → 持久事件/SSE、取消、幂等、重连与恢复已验收；没有外部 provider 的真实 key 或付费模型调用，因此上述“真实模型消息”退出项仍待配置后验证，不能把协议 fixture 冒充真实模型验收。上传和真实工具/审批不在本切片开放。该段为当时状态；真实外部模型退出项已于 2026-11-04 完成，见本节末尾记录。

### 2026-11-03 继续切片：P2 聊天、模型流、SSE 与启动恢复

- [x] 重新建立 P1 门禁并通过 `mvn verify`，再改动剩余 P2 生产代码。
- [x] `plugins/model-providers`：`OpenAiCompatibleAdapter`、`StreamAssembler`、`RetryClassifier`；Java 17 HttpClient 异步发起 + 有界专用 Reader 池，支持工具分片与 finish 后 usage，覆盖取消、超时、HTTP 错误和截断；凭据不进入日志/事件。
- [x] `platform/security` 与 `platform/recovery`：本地令牌、严格 Host/Origin、HttpOnly/SameSite cookie、开发 CORS、启动锁及不重放模型的恢复。bootstrap 装配，Flyway 前持锁，接请求前恢复，关闭先停 Agent 再关连接。
- [x] `features/providers/application`、`features/assistants/application`、`features/chat/application/agent/api`：配置创建/读取、对话创建/查询/归档、文本发送、取消；短事务保存 inbox 与幂等响应，响应完成后唤醒 Agent。原子创建组合仍经唯一 SessionCommitService；HTTP 不写会话事件。
- [x] `platform/sse`：先订阅缓冲再获取高水位并分页回放，按 sessionId:seq 去重；每连接有界队列、单发送者、15 秒心跳、慢连接断开重放，delta 不分配持久 id。
- [x] 实现首批 REST 的 OpenAPI、SSE durable/ephemeral 目录和 ProblemDetail 错误码；浏览器 SSE 使用 cookie，不能要求自定义 header。
- [x] 以本地协议服务器和临时 SQLite 验证真实 HTTP→202→模型协议流→持久事件/SSE、取消、幂等重放与冲突、重连和恢复；不连接用户数据或发起付费模型调用。
- [x] 独立复核与全模块验证收敛后，删除本次新增测试、案例与临时产物，再做干净构建，按真实退出标准记录 P2 状态。

本次收尾的六项红绿回归：

1. 原生 EventSource 使用初始 `after=0` 自动重连时，更新后的合法 `Last-Event-ID` 优先，不能因二者不同而返回 400。
2. `CancelOptions` 新增可选 expectedTurn / `forTurn(int)`，保留旧三参构造器；取消意图与 turn 边界同步，过期请求不能误取消下一轮，旧 deadline 也不能跨轮生效。
3. 在 inbox 事务之前预留 64 个会话执行/激活名额，满载的新输入返回 503、不写 inbox；响应完成后的激活不再依赖可能拒绝任务的命令队列。
4. 模型 deadline 定时器只解除 I/O 阻塞，终止回调在对应流的 worker 执行；一个阻塞消费者不会拖住其他流的 deadline。
5. 启动和运行期租约回收统一使用 max-inbox-attempts，启动后才过期的耗尽租约同样取消。
6. 有序 ContextClosedEvent 协调在 Web Server 等待 SSE 之前停止接单、向全部 Agent 发 Shutdown、排空并关闭 SSE，不在关闭等待期启动排队的付费 turn。

2026-11-03 收尾验收：首次全模块 `mvn clean verify -B` 通过 44 项；最终独立复核指出旧激活回调可能释放新请求预留的竞态。已补两项红绿回归，使用每请求独立的预留令牌、同会话容量去重，以及等待激活期间的空闲写者淘汰保护修复；最终全模块验收通过 **46 项**（P1 9、模型协议 10、聊天/安全/恢复 19、契约 3、投影迁移 1、原架构门禁 4），无失败、错误或跳过。复核无保留的 Critical/Minor，Important 已由失败回归、修复和完整套件收敛。

按用户要求，随后已删除 `P1GateTest`、`ModelProtocolAcceptanceTest`、`P2ChatAcceptanceTest`、`P2ContractAcceptanceTest`、`P2ProjectionMigrationTest` 五个临时测试源码，以及 dist 下测试探针、测试日志、隔离编译目录/依赖和临时 SQLite DLL；保留 dist/.gitkeep、原 P0 ArchitectureRulesTest 及两个负向 fixture。清理后再次执行全模块 `mvn clean verify -B` 成功，**4 项原架构检查通过**，无失败、错误或跳过。源码与 target/test-classes 清单确认无已删除功能案例残留；以后 verify 不会重新运行被删除的 **42 项功能案例**。

当前构建采用工作区已有的 Boot 4.1.2 / Jackson 3 / Jupiter 6，并非本次收尾另行升级。模型流保留内核契约允许的 adapter 自有有界阻塞 Reader 池（每适配器 16 路），不声称全程非阻塞。依赖分析仍报告 Jackson annotations/core 与测试 JUnit API 的传递依赖声明警告，配置为报告而不阻断；不把本次构建描述为零警告。

本地浏览器认证的边界：Host/Origin/cookie 防止外来网页请求，bootstrap 不抵抗可伪造 HTTP 头的恶意本地进程或其他本机 OS 用户。工具、上传和 SSRF 出口策略随 P3/P4 实际能力接入；没有上传端点可供绕过。

当前聊天切片只接受文本内容，工具/工作区副作用仍在 P3 由真实能力和审批接入；不提供伪成功的 Shell/文件实现。provider 仅保存 credential_ref，真实 key 从环境变量/私有配置按引用读取。未完成的端点不写进已实现契约。

#### 本次执行裁定与未验证边界

- 沿用无 Git 元数据的现有目录，不运行 worktree/提交/合并脚本；代价是没有提交历史可恢复，结果保存在本计划与生产文件中。
- 把用户“按文档继续开发”解释为继续已有 P2 文本聊天切片，而非扩大到 P3–P5；若用户原意是后续阶段，那些能力仍未交付。
- 允许必要的 agent-core 取消选项/turn 边界修订：backend 的数据库检查不能原子阻止迟到取消影响下一轮；代价是维护兼容的内核 API 扩展。
- 保留每适配器 16 路阻塞 Reader 的既有方案；代价是每流线程开销及达到上限后的可重试过载，不承诺全程非阻塞。
- 本地 bootstrap 不隔离恶意 OS 进程/其他本机账户；若需要该边界，当前认证不足，须另行引入系统级隔离。
- 独立复核未执行外部付费模型与 Maven；本会话已实际完成 46 项功能/架构验收及清理后的 4 项门禁，但真实外部 provider 仍可能有未覆盖的协议差异，退出项保持待验证。
- 独立复核未测容器压力下的 socket 级慢连接断开时延；已验证应用有界队列/准入和重连，不承诺测得的断开时延，慢客户端可能占用发送者直到超时或网络关闭。

本次无延期 Minor。最终收尾技能的分支集成步骤在非 Git 环境不适用；未创建仓库、提交或对外发布。工作流 scratch ledger 的必要结果和裁定已归档到本节，再清理本计划的临时工作目录，不处理其他计划目录。

### 2026-11-03 P2 退出复核（进入 P3 前）

用户再次要求检查 P2 并在完成后继续后续阶段；当时在“先真实模型验收”与“保留该项先开发 P3”之间选择前者，因此当时没有提前启动 P3。随后用户再次要求“依次类推，p1、p2完成之后则开始后续阶段的开发”。当前执行解释是继续技术上不受模型配置阻塞的 P3，再依次 P4/P5，不逐阶段询问是否继续；**P2 正式外部模型退出仍未完成**，不将该执行解释写成用户明确豁免或本地 fixture 已通过真实模型验收。

本轮复核补齐了两项归档生命周期问题：

1. 延迟激活在 pending 查询后，必须在与归档相同的生命周期 monitor 内重新读取 ACTIVE 状态，再唤醒写者；归档完成后旧回调不能新建写者。
2. 归档取消后，必须在数据库事务和全局 monitor 之外 `retire` 写者，确认实际排空才移除 handle；保留未认领输入但不执行。否则带排队输入的归档会话会永久占用容量，累计 64 个后新会话一直 503。无法确认排空时保留容量并返回 RECOVERY_REQUIRED，原幂等归档请求重放可重试退休。

验证记录：

- 两项缺陷都先动态复现再修复：第一项实际收到归档后不应出现的模型请求；第二项累计 64 个真实写者后，新会话发送实际抛出 OVERLOADED。
- `P2LifecycleRegressionTest` 四项临时回归覆盖迟到激活、归档后幂等重放、当前写者取消且不启动排队输入、64 个归档写者容量回收。
- 修复后全模块 `mvn clean verify -B`：**8 项通过**（上述 4 项 + 原架构 4 项），无失败、错误或跳过；不声称重新运行上轮已删除的 42 项案例。一次非 clean 验证曾因编译输出缺失而在 testCompile 失败，工作区同时存在开发监听器；保持源码不变后干净构建成功，未停止用户开发进程。
- 启动真实可执行 JAR、临时 SQLite 和本地协议服务器，完成 **6 项实际运行检查**：本地认证/外来 Origin 拒绝、HTTP 202→模型协议→持久 SSE/消息/usage、幂等重放与冲突、原生重连游标优先级、取消保留部分输出、真正强制终止该测试 JVM 后重启补 interrupted 且不重放模型。修复后的最终 JAR 再次通过全部 6 项。这不是外部真实模型验收。
- 临时回归源码及本轮隔离运行探针/数据库已删除；清理后再次全模块 `mvn clean verify -B`：**4 项原架构检查通过**，无失败、错误或跳过，源码及 target/test-classes 无该临时案例残留。Jackson annotations/core 的非阻断传递依赖声明警告仍存在。

真实模型验收待补的信息是：选定 OpenAI-compatible `baseUrl`、实际 `model`、可选 `credentialRef`（如 env:MY_MODEL_API_KEY）。已检查的当前进程常用模型变量未设置，项目 config/application.local.properties 不存在；未读取用户库、扫描其他私有凭据或复用开发工具自身的凭据。密钥应仅在本机环境/私有配置中提供，不发到对话。待配置后使用隔离数据库和最小非敏感提示完成一次真实 HTTP→SSE 验收并记录实际结果；此项与当前不依赖真实模型配置的 P3 代码开发分开跟踪。上述待补项已于 2026-11-04 由用户明确授权后完成，见下节。

### 2026-11-04 P2 真实外部模型退出验收

用户明确选择使用当前环境已有的 `DASHSCOPE_API_KEY` 完成一次最小外部验收；密钥只通过运行进程环境传递，未写入对话、配置、数据库、事件或日志。验收使用隔离临时数据目录和 `task5-acceptance` 干净快照构建的可执行 JAR（SHA-256 `1908e25fda4b51727ba3de17c0709c16126eb6b8b322a294a6687fedaf07b70f`），provider 为 `credentialRef=env:DASHSCOPE_API_KEY`，`baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1`，`model=qwen-plus`，提示为一条非敏感短句。

实际结果（证据：[p2-real-provider-acceptance.json](../.superpowers/sdd/2026-11-03-p3-tools/p2-real-provider-acceptance.json)）：

- 发送返回 HTTP 202；SSE 收到实时 `assistant.delta`（文本 `OK`）、`usage` 和 `turn.ended`。
- 持久事件 `turn/end` 为 `completed`；消息投影产生 `ASSISTANT` / `COMPLETE` 的 `OK`；`usage_records` 记录 `qwen-plus`、输入 876 / 输出 1 tokens、attempt 1。
- 应用日志未出现密钥值；未使用项目 data 目录，未读取用户库或其他私有凭据。
- 同一端点上 `qwen-turbo` 返回 provider 侧 HTTP 429 `limit_requests`；改用 `qwen-plus` 后成功。这是该账号/模型的配额状态，不是代码缺陷，也不代表其他 provider 已通过。
- 结合此前可执行 JAR 的崩溃重启不重放模型、审批等待不占数据库连接检查，P2 的三项退出标准均已具备实际证据。真实外部验收只覆盖一个 provider/model 的 OpenAI-compatible 协议兼容性，不扩大为所有 provider 差异已验证。
- 2026-11-04 P3 审查修复波后，最终 JAR（SHA-256 `32a4233a16b22c581ca4e1de2fa27c80add5e1f524b80e61cd3d04c6e7da9159`）再次完成同一最小验收：5/5，SSE `OK`，持久 completed turn，usage 863/1 tokens；证据：[p2-real-provider-final.json](../.superpowers/sdd/2026-11-03-p3-tools/p2-real-provider-final.json)。

## 5. P3：工具、审批与工作区

P3 已按 [P3 设计](superpowers/specs/2026-11-03-p3-tools-design.md) 与 [实施任务](superpowers/plans/2026-11-03-p3-tools.md) 完成并通过阶段验收。依架构 §13，v1 交付明确标识的 pipe terminal，真实 PTY 为可选后续适配，不以普通管道或空 resize 冒充。

用户补充要求：各阶段完成后删除相应临时测试文件。执行顺序为该阶段功能验收和审查收敛 → 删除该阶段新增临时测试、runner 与隔离数据 → `mvn clean verify` 确认原架构门禁和无 class 残留；不删除正式生产代码、原 P0 测试/fixture 或用户数据。

| 任务 | 内容 | 验收 |
| --- | --- | --- |
| P3-1 | `builtin-tools`：read/write/edit/glob/grep/shell/terminal | 路径穿越、符号链接、命令注入测试 |
| P3-2 | `workspace-runtimes`：本地文件、进程、明确 PIPE 的终端、后台任务；PTY 可选 | 同一工作区根；超时与取消可 drain；不支持 resize 明确报错 |
| P3-3 | 审批全链路 | `ASK`/`NEVER`、单次授权、超时=取消、重启=unavailable |
| P3-4 | 工具并发与输出 | `concurrencySafe` 顺序屏障；输出超限 spill 到 `data/tool_outputs` |
| P3-5 | 恢复与审计 | `approvals` 投影（V7）与事件对一致 |

2026-11-04 阶段验收：P3-1～P3-5 的生产实现已落地。工作区运行时（Windows Job Object 进程归属、有界双流捕获、PIPE 终端、后台任务）与七类内置工具各自模块验收通过；backend 完成 V7 审批/输出投影、持久审批 broker、审批与输出 HTTP 端点、assistant 权限字段（严格校验 + 旧 JSON 兼容），以及 ChatAgentService 的真实会话执行环境装配。

最终验收与修复：

- 隔离 source-only 快照 `mvn clean verify -B` 通过 **83/83**（P3 功能 79 + 原架构 4，失败/错误/跳过均为 0），`sourceStable=true`。
- 最终构建可执行 JAR（SHA-256 `30af0ef9ec321804ff30edb4fa99ce6723d40c0dd9795b9d23928fffee302921`）通过 P2+P3 共 **14/14** 项实际运行检查，SSE 断开转换错误 0。
- P2 真实外部模型验收在 P3 修复波 JAR 上重跑通过 **5/5**（SSE `OK`、持久 completed turn、usage 863/1 tokens）；该 JAR 与最终 JAR 的生产源码逐文件一致，仅测试源码不同。证据：[p2-real-provider-final.json](../.superpowers/sdd/2026-11-03-p3-tools/p2-real-provider-final.json)。
- 独立 reviewer 发现 3 项 Important、6 项 Minor。已修复 Important：审批参数精确查找（不再受 64 条窗口限制）；runtime spill 引用提升为可读的持久 `out_` 引用并写入 `ToolResultEvent.metadata`；V8 会话级策略覆盖与每 turn 起始解析/冻结。另修复 READ_ONLY 硬上限、输出配额下溢、terminal schema、read/grep 262144 字节上限与严格 UTF-8。保留 2 项已记录 Minor：取消与 HTTP CAS 之间返回 202 的窗口（writer 仍结算 cancelled，副作用不执行）；broker `settled` 表随审批数量增长。scoped re-review 确认 3 项 Important 均已解决，无新增 Critical/Important。
- 按用户要求删除本阶段 10 个临时测试源码、4 个 runner、隔离快照/临时数据库与生成测试 class，保留原 P0 `ArchitectureRulesTest` 和两个 fixture；清理后隔离 `mvn clean verify -B` 仅执行 **4 项原架构检查**，全部通过，`sourceStable=true`，源码与 `target/test-classes` 无已删除案例残留。清理后不再复跑被删除的功能案例。经用户确认终止 4 个早前遗留的 fixture JVM 并删除其临时目录后，项目目录直接 `mvn clean verify` 也通过 4/4，全模块 BUILD SUCCESS。
- v1 交付边界：terminal 是明确 PIPE、resize unsupported，不是 PTY；shell/terminal 是宿主账号权限的任意执行，不是 OS 沙箱；本地执行环境仅验收 Windows；真实外部模型只覆盖 DashScope `qwen-plus` 的 OpenAI-compatible 兼容性。本段记录 P3 验收时的边界；P4/P5 当前交付见后续章节。

## 6. P4：上下文、技能、记忆、搜索、MCP

P4 于 2026-11-04 完成核心交付与阶段验收。

1. `context`：`PromptSection`/`SystemPromptAssembler`、`TokenMeter`、`CompactionPlan`/`CompactionPlanner`/`CompactionExecutor`；提示词模板只读，占位符白名单；压缩只改 surface，原文事件不删除。
2. `compaction`：自动 step 前压缩、`POST /sessions/{id}/compaction` 手动压缩、`POST /sessions/{id}/compaction/{seq}/abandon` 放弃；重启恢复把未配对 start 配对为 end(error=interrupted)，不重放付费摘要。
3. `skills` + `prompts`：`SkillSummary`/`Skill`/`SkillSource`、backend `SkillCatalog`、内置 `local-workspace` 样例、系统提示词只含 name+description、`skill` 工具按需加载正文。
4. `memory`：Markdown + FTS5（V9）、`MemoryStore` 端口、`memory_search`/`memory_read` 工具、HTTP 列表/读取/保存/删除/搜索/refresh、手动提取到 `data/memory/inbox`；可选 `rikkahub.memory.extract-on-turn`（默认 false）。
5. `usage`：按会话、模型、日期聚合 token/成本，只读 API。
6. `search`：`SearchPort` + SearXNG 适配器 + `web_search` 工具；未配置时工具明确 unavailable。`mcp-client` 提供 `mcp__<server>__<tool>` 命名适配器与 `McpPort`；真实 MCP server 连接仍需配置。`translation`/`speech`/`media` 端口与显式 unavailable 能力已定义；真实适配器按配置后续接入。
7. `capabilities`：`GET /api/v1/capabilities` 明确报告未配置能力，不伪装可用。

退出标准验证：

- 压缩后原文仍可从事件日志重建：自动/手动/放弃/恢复回归通过。
- 技能正文不能覆盖系统提示词或审批策略：正文不进入 system prompt；工具权限仍由 `ToolPolicy`/审批管线决定。
- MCP 工具名使用 `mcp__<server>__<tool>`：适配器与命名回归通过。
- 未配置外部能力明确 unavailable：`capabilities` 与 `web_search` 行为通过。
- 隔离 source-only 全量 `mvn clean verify`：26 项（P4 功能 22 + 原架构 4）全部通过；随后按用户要求删除本阶段临时测试与 runner，清理后仅保留原 4 项架构检查。

本节为此前 P4 阶段记录。本轮已补 MCP 2025-11-25 Streamable HTTP 传输、显式连接、分页发现、工具调用与真实连接状态；翻译/语音/媒体仍依架构按需接入，未配置时保持 unavailable。P5 当前完成状态见下节。

## 7. P5：超智能体编排

1. `agent-core/orchestration` 领域模型与 `Engine` 五部分。
2. `effects`/`outbox` + `PassScheduler` + `Watchdog`。
3. `Ticket`/`Assignment`/Worker 会话 + `data/workspace/tickets`。
4. 可选 `Source`：定时、Webhook、GitHub/Feishu。
5. `Eval` 双层判定与 `Mandate` 授权；默认 `observe`，不自动接单。

退出标准：重启后最老的 Ticket 优先恢复；`stored work first`；人的消息可以 resume 同一 Mandate 且不触发新 Eval。

### 2026-11-04 P2–P5 完整收尾

- P2/P3/P4 原有主链路重新经过可执行 JAR 与浏览器复核；没有再发起真实付费外部调用。既有 P2 真实模型验收记录保持有效，本轮协议 fixture 与真实外部验收分开记录。
- 新增 `agent-core/orchestration`：Trigger/Thread/Eval/Decision/Ticket/Mandate/Assignment/Reply、Engine 五部分与 WorkerSlotManager；默认 observe，建议不能接单，resume 不新建 Eval，授权撤回/到期拒绝执行。
- 新增 V10：独立聚合行、effects/outbox 与 interval Source。会话 effects 同事务提交；租约 token fencing、容量推迟、非幂等未知效果 DEAD 与人工检查确认。
- Worker 使用独立 `worker-<ticketId>` 会话、固定配置与 tickets 工作目录；worker_report 持久汇报，实际 drain 后释放槽位；人的补充消息持久去重，沿用同一 Mandate。
- 建议评估默认关闭，显式请求或 host 配置后只产生建议；无工具、调用前持久尝试标记、usage purpose=eval；中断不自动重发。定时 Source 支持 tick 去重、启停；统一 Webhook 仅受认证本地 intake。
- Watchdog 按最老 Ticket 恢复，标记 BLOCKED/WAITING、取消过期 Assignment 残余输入；明确用户确认后再携带 recovery note 继续，不自动重放模型/Shell。
- 补 MCP HTTP 传输、连接管理和工具注册，以及 React 工作台的任务/待办、对话/SSE、审批、记忆、用量、模型配置。可选专用 GitHub/Feishu、公网 Webhook、翻译/语音/媒体仍遵循架构按需接入，不虚报已实现或可用。
- 本轮发现并修复 P3 Windows 过度 argv 引号导致显式 cmd 内置命令失败、P4 中文片段无法被 unicode61 搜索、移动端侧栏最小宽度溢出，以及提交成功回调覆盖新草稿。相关用例均先观察失败再验证修复。
- 模块验收 **33/33**：Engine 13、MCP 3、Windows 原生运行 2、Worker/Spring/记忆/定时 Source 6、SQLite 队列 5、原 P0 架构门禁 4。全量 clean verify 与补充全量 test 均通过，失败/错误/跳过为 0。
- 真实本地 JAR + Vite + Chrome 验收 **19/19**，另有延迟提交草稿回归 **1/1**：配置、observe、Worker/resume、Markdown/FTS、用量、聊天 SSE、单次审批与真实 Shell、认证/Origin、移动布局、强制终止 JVM 后不重放、Watchdog 待办、恢复提示与持久去重；浏览器未捕获 JavaScript 错误。
- 独立只读审查没有保留的 Critical/Important；草稿覆盖问题已按实际数据丢失风险修复。前端 build/lint、OpenAPI YAML 与 17 个外部 Path Item 引用校验通过。
- 操作、配置、可选边界及恢复语义集中在 [orchestration.md](orchestration.md)。
- 按用户要求删除本轮 5 个临时 JUnit 源码、2 个浏览器 runner、隔离数据库/工作目录和截图。清理后全模块 `mvn clean verify -B` 再次成功，只运行 **4/4 原 P0 架构门禁**；前端 build/lint 成功，无已删除测试 class 残留。功能用例删除后，后续构建不能复跑上述 29 项功能案例。验收汇总保存在 `.superpowers/p2-p5-acceptance.json`（本地忽略的证据文件，无凭据或用户数据）。

## 8. R1：质量与回归网 (自动化 Testkit)

R1 于 2026-11-04 完成交付与验证：
1. **模块交付**：根 reactor 新增 gent-core-testkit 模块（第 12 个模块），仅依赖 gent-core，通过 Enforcer 严禁依赖 Spring/后端产品与插件。
2. **测试替身**：
   - ScriptedLlmAdapter：支持可编程流式推理（ReasoningDelta、TextDelta、ToolCallDelta、Usage），支持取消与完整请求断言。
   - FakeExecutionEnvironment：在临时内存与目录下模拟安全工作区、文件系统及进程运行，不触碰宿主 Shell。
   - TestFixtures：提供测试时钟、自增 ID 生成器与默认冻结配置。
3. **验收与清理**：
   - 临时功能验收覆盖流式、文件、取消、工具管线与编排 Engine 5 大链路，通过 5/5。
   - 验收后彻底清理临时测试源码，全模块 mvn clean verify -B 通过原有 4 项 ArchUnit 架构门禁。
   - 产物指纹固化：ackend-1.0-SNAPSHOT.jar SHA-256 为 170b1dfb62cde224ba080c87cc665ef6157f4693e51ec31db22bb8098b25c6b，详见 .superpowers/r1-acceptance.json。

## 9. R2：本地单机发行闭环 (一体化打包/桌面)

R2 于 2026-11-04 完成交付与验证：
1. **前端资产内嵌与 SPA Fallback**：更新 `frontend/index.html`（Title: `RikkaHub Agent`），通过 `maven-resources-plugin` 在打包阶段将 Vite 产物拷贝内嵌至 `backend/src/main/resources/static/`。实现 `SpaWebConfiguration`，客户端路由（`/chat`, `/tasks`, `/memory`, `/settings`, `/usage`）自动回退至 `index.html`，同时保护 `/api/**` 与 `/sse/**` 的严格 API 404/405 语义。
2. **独立数据目录机制**：重构 `StoragePaths`，支持 `~` 展开、`RIKKAHUB_DATA_DIR` 环境变量、开发态（`./data`）与桌面态（Windows `%LOCALAPPDATA%\RikkaHub`，Unix `~/.rikkahub`）隔离，并在系统启动时完成 9 大子目录初始化与可写权限预检。
3. **桌面启动器与发行产物**：交付 Windows 启动脚本 `dist/bin/start-rikkahub.bat`（Java 17+ 检测、环境变量支持、默认浏览器拉起）、Linux/Mac 脚本 `dist/bin/start-rikkahub.sh`、发行说明 `dist/README.md`，组装一体化 Fat JAR 到 `dist/lib/backend.jar`（38.5MB）。
4. **验收与清理**：
   - 临时验收套件（`R2DistributionAcceptanceTest`）覆盖静态资源托管、SPA 回退、API 错误保护、StoragePaths 权限与波浪号展开、启动器脚本与 JAR 资源检验，5 项测试全部通过（5/5）。
   - 彻底删除临时测试源码，全模块 `mvn clean verify -B` 12 模块通过（20.7s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
   - 产物 SHA-256：`dist/lib/backend.jar` 指纹为 `bb5b857c0e47d0bc455bcb0990dee0b7705aebe7abca01b11c7d28af2a5fc200`，详见 `.superpowers/r2-acceptance.json`。

## 10. R3：完整备份与容灾恢复 (快照导出/恢复)

R3 于 2026-11-04 完成交付与验证：
1. **一致性全量备份归档**：
   - 交付 `BackupRestoreService` 与 `BackupArchiveManifest`、`BackupArchiveInfo`、`RestoreResult`。
   - 利用 SQLite `VACUUM INTO` 取得无事务锁热备快照，联动打包 `database/`、`memory/`、`skills/`、`workspace/`、`tool_outputs/` 为带校验清单（`manifest.json`）的标准 `.rkpack`（ZIP 格式）归档。
   - 清单记录文件相对路径、字节大小与 SHA-256 哈希值；敏感凭据严格脱敏排除（`CREDENTIALS_EXCLUDED`）。
2. **安全还原与无副作用恢复**：
   - 校验环节：防御 Zip Slip 路径穿越，解压前严格核对归档内全部文件的实际 SHA-256 签名，篡改或损坏立即拒绝恢复。
   - 在线热恢复：利用 SQLite 驱动原生 `restore("main", stagedDb, null)` 实现连接池在线热恢复，同步原子还原各数据目录。
   - 状态收敛与 Watchdog 扫描：恢复完成后触发 `RecoveryService.recover()` 与 `Watchdog.recover()`，清扫未闭合 turn 并标记为 `INTERRUPTED`，将挂起审批标记为 `unavailable`，坚决不自动重放未完成的模型调用或 Shell 副作用命令。
3. **HTTP REST API 暴露**：
   - `POST /api/v1/system/backup`：触发一致性热备导出，返回 201 与 `BackupArchiveInfo`。
   - `GET /api/v1/system/backups`：列出本地历史备份列表（按时间倒序）。
   - `GET /api/v1/system/backups/{filename}`：流式下载二进制备份归档，设置 `Content-Disposition: attachment`。
   - `POST /api/v1/system/restore`：接收备份文件名参数，安全执行容灾恢复并返回详细 `RestoreResult`。
4. **验收与清理**：
   - 临时验收套件（`R3DisasterRecoveryAcceptanceTest`）覆盖快照创建与清单完整性、未认证拦截与下载目录穿越防御、Zip Slip 与哈希篡改检测拦截、损坏后全量恢复与 SQLite 热恢复验证、REST API 端到端还原，5 项测试全部通过（5/5）。
   - 彻底删除临时测试源码与临时归档，全模块 `mvn clean verify -B` 12 模块通过（31.5s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
   - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `e171a55e335f1d90272d0c883520f7a94ca58ed54ec62532daa33529f9e1c9d1`，详见 `.superpowers/r3-acceptance.json`。

## 11. R4：生产级沙箱与 PTY 终端 (ConPTY/容器隔离)

R4 于 2026-11-04 完成交付与验证：
1. **Windows ConPTY 原生伪终端适配**：
   - 解决交互式命令在普通 PIPE 模式下无法接收键盘交互与终端尺寸调整的问题。
   - 基于 JNA 动态绑定 Windows kernel32 原生 `CreatePseudoConsole`、`ResizePseudoConsole` 与 `ClosePseudoConsole` 导出函数，实现与子进程的双向真实 ANSI 转义序列流动与伪控制台句柄生命周期管理。
2. **自适应终端模式与平滑降级**：
   - 交付 `ConPtySupport` 动态探测环境支持，`LocalTerminal` 与 `LocalExecutionEnvironment` 优先启用 ConPTY（支持实时动态 resize(cols, rows)），不支持或非 Windows 平台安全无缝回退至标准 PIPE 管道，零崩溃风险。
3. **内置工具升级**：
   - 扩展 `BuiltinTools` 终端控制指令，支持 `resize` 子操作，更新 tool schema 与参数白名单校验，允许前端与编排按需调整终端视口。
4. **容器隔离引擎探测与装配**：
   - 交付 `SandboxDetector` 与 `ContainerSandboxRunner`，自适应探测系统中 Docker/Podman 可用性（1.5s 安全超时），未就绪平滑回退为本地 LOCAL 模式；就绪时支持 `--rm`, `--memory`, `--cpus`, `--network none` 以及受限单工作区挂载，支持中止销毁与超时强制终止。
5. **能力探针 API 暴露**：
   - 在 `CapabilitiesController` 中提供 `GET /api/v1/capabilities`（含 `pty` 与 `sandbox` 标记）以及 `GET /api/v1/capabilities/sandbox` 返回沙箱引擎驱动状态与详细隔离策略。
6. **验收与清理**：
   - 临时验收套件（`R4SandboxAndPtyAcceptanceTest`）覆盖 ConPTY 启动与输入输出、实时 resize、安全回退 PIPE 降级、SandboxDetector 探测超时与兜底、GET /api/v1/capabilities 端点校验，5 项测试全部通过（5/5）。
   - 彻底删除临时测试源码，全模块 `mvn clean verify -B` 12 模块通过（31.1s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
   - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `4832A2C16DDA67BBFC57485753F7F91398077AFF6C4ED6F633F5E45F758288F8`，详见 `.superpowers/r4-acceptance.json`。

## 12. R5：协议与工具扩展 (MCP Stdio/多厂商原生适配)

R5 于 2026-11-04 完成交付与验证：
1. **MCP 2025-11-25 Stdio 本地子进程客户端**：
   - 交付 `StdioMcpClient`，支持通过标准输入输出管道拉起并交互本地开源 MCP 服务器（如各类开源 stdio 工具服务）。
   - 遵从 MCP 2025-11-25 规范，支持初始化能力协商、`notifications/initialized` 通知、`tools/list` 游标分页拉取（死循环与恶意超量防御）、`tools/call` 结构化参数调用与 `CancellationToken` 取消，退出时强制销毁子进程。
2. **Anthropic Claude 原生流式适配**：
   - 交付 `ClaudeMessagesAdapter`，支持 `/v1/messages` 接口体系；支持 `system`、多轮 `messages`、`tool_use`、`tool_result` 与 Base64 图片多模态；支持 SSE 流式事件解析与 `thinking_delta` 思维链推理块提取（归一化为 `StreamChunk.ReasoningDelta`）、`input_json_delta` 工具调用分片组装以及 `TokenUsage`。
3. **Google Gemini 原生流式适配**：
   - 交付 `GeminiNativeAdapter`，支持 `streamGenerateContent?alt=sse` 接口；支持 `systemInstruction`、`contents`（`user`/`model`）、多模态 `inlineData`、`functionDeclarations` 与 `functionResponse`；支持提取 Gemini 2.0 Thought 思考过程与 `functionCall` 结构化工具调用。
4. **ProviderApplicationService 多厂商统一分发**：
   - 扩展数据库与应用服务，支持 `openai-compatible`、`anthropic`、`gemini` 三大生态统一注册、参数与凭据校验；将 `adapter(id)` 升级为多态 `LlmAdapter`，向聊天、压缩、记忆提取与编排模块透明暴露。
5. **验收与清理**：
   - 临时验收套件（`R5ProtocolAndToolsAcceptanceTest`）覆盖 Stdio 子进程握手调用退出、Claude 原生流与 Thinking 解析、Gemini 原生流与 FunctionCall 提取、Provider 多厂商分发与异常拦截、适配器幂等安全关闭，5 项测试全部通过（5/5）。
   - 彻底删除临时测试源码，全模块 `mvn clean verify -B` 12 模块通过（32.7s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
   - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `FB2D70E4E3085C9B130B5B046041E133EA71736F935F4A6351EC2FBA299E2FCA`，详见 `.superpowers/r5-acceptance.json`。

## 13. R6：协同与外部通道扩展 (外部IM/Subagent网络)

R6 于 2026-11-04 完成交付与验证：
1. **外部 Trigger 加密防伪验签网关**：
   - 交付 `WebhookSignatureVerifier` 与 `ExternalWebhookController`，支持主流通道（GitHub HMAC-SHA256、Slack v0 时钟偏移防重放、飞书 X-Lark-Signature 与 URL challenge 验证、通用 HMAC 签名），未授权或篡改请求一律 401 阻断。
2. **DeepSeek 安全铁律（默认 Observe 决策）**：
   - 外部事件进入系统统一封装为 `Decision.OBSERVE` 待办，外部输入绝不能自动授权工具执行或产生副作用，必须由用户审阅后显式 `accept` 授权生成 Ticket。
3. **Outbox 外部异步可靠投递**：
   - 交付 `OutboxDispatcherService`，支持从持久化 `outbox` 队列中租约申领，通过 HTTP POST 投递至外部企业微信/飞书/Slack 机器人或通知 URL，投递成功状态原子转换为 `DONE`，网络异常支持失败重试与死信兜底。
4. **Subagent 协同网络与隔离生命周期**：
   - 交付 `SubagentCoordinatorService`，支持主 Agent 委派专门子任务，自动创建独立受限子工作区（`workspace/subagents/{taskId}`）与隔离会话，支持级联取消与结果汇总。
5. **REST 端点暴露**：
   - 在 `OrchestrationController` 提供 `POST /api/v1/orchestration/subagents/delegate`、`GET /api/v1/orchestration/subagents/{taskId}`、`POST /api/v1/orchestration/subagents/{taskId}/cancel`、`GET /api/v1/orchestration/subagents` 以及 `POST /api/v1/orchestration/outbox/dispatch`。
6. **验收与清理**：
   - 临时验收套件（`R6CollaborationAndChannelsAcceptanceTest`）覆盖 Webhook 签名与防重放拦截、外部输入默认 Observe 安全隔离、Outbox 异步可靠投递收敛、Subagent 委派与级联取消、控制器 REST 端点连通性，5 项测试全部通过（5/5）。
   - 彻底删除临时测试源码，全模块 `mvn clean verify -B` 12 模块通过（31.7s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
   - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `17FFD32E26E1C0BAD0E2BDF4E99BACC4781DE9BB705648680A3C627B9CE06B34`，详见 `.superpowers/r6-acceptance.json`。

## 14. 测试与构建门禁

| 层 | 工具 | 必须覆盖 |
| --- | --- | --- |
| `agent-core` | 本轮临时 JUnit Jupiter 6 案例 | 已验收事件、surface、提交、lease、取消、工具与循环；案例按要求清理，后续回归须重新准备 |
| `plugins` | 契约测试 + fixture | 流式归一化、MCP transport、工作区路径 |
| `backend` | `@SpringBootTest` + `@TempDir` | Flyway、JdbcClient 仓库、SSE、审批、恢复 |
| `tests/compatibility` | golden 事件日志 | 新代码读旧格式 |
| `tests/e2e` | 前端 + 后端 | 一条完整消息流 |

门禁：

- 当前 `mvn clean verify` 检查全模块构建、enforcer 和保留的 P0 ArchUnit 测试；P2/P3/P4 的功能/迁移验收案例已按用户要求在阶段验收后删除，清理后的构建只执行 4 项原架构检查，不能复现已删除功能场景。P5 的 29 项功能案例与对应 runner 也在本轮阶段验收后清理；清理后只保留原 4 项架构检查。`-DskipTests` 不用于验收。
- 新增事件类型必须有 record、JSON 往返测试、旧日志兼容策略。
- 新增副作用工具必须声明幂等性与恢复策略。
- 新增 SSE 事件必须标注 durable/ephemeral 与回放行为。

```powershell
mvn clean verify
mvn -pl agent-core -am test
mvn -pl agent-core dependency:tree
```

第一条命令先移除旧 `target`，避免误运行已删除临时测试的残留 class。第二条在清理后只验证当前模块构建与实际保留的测试，不能复现本轮 P1 临时案例。

## 13. 提交前检查清单

- [ ] `agent-core` 的 `mvn dependency:tree` 没有 Spring、JDBC、servlet。
- [ ] 新增的 `plugins/*` 只依赖 `agent-core`。
- [ ] 跨 feature 调用只经过 `application` 或共享端口。
- [ ] 没有把 `Conversation`/`Assistant` 等产品实体传进 `agent-core`。
- [ ] 流式 token 没有逐字写库。
- [ ] 审批、Shell、模型流期间没有持有数据库连接。
- [ ] 新表有 Flyway 迁移、索引、备份影响说明。
