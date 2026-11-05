# P2 聊天闭环收尾实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 延续现有 P2 设计与工作区实现，完成可验证的 HTTP → inbox → 模型流 → 事件/消息投影 → SSE 链路，以及取消、幂等、本地安全和启动恢复。

**Architecture:** 保留 agent-core / plugins / backend 边界；模型协议使用 JDK HttpClient，产品端点经过 application，所有会话事件写入经过 SessionCommitService。先核验现有未记入进度文档的代码，不重新搭建、不把 P3 工具或工作区能力提前混入。

**Tech Stack:** Java 17、Maven 3.9+、现有 Spring Boot/Jackson 依赖、SQLite/JdbcClient/Flyway、JUnit、JDK 本地协议服务器。

**Spec:** [backend-architecture.md](../../backend-architecture.md)、[backend-implementation-plan.md](../../backend-implementation-plan.md) §4、[kernel-api-contract.md](../../kernel-api-contract.md)。用户已要求按这些文档继续开发，本计划细化现有任务，不另设产品设计。

## Global Constraints

- 纯本地单用户；默认只监听 `127.0.0.1`。
- Java 17 语言/字节码基线；当前 JDK 22 仅作为构建运行时。
- 第一个 provider 为 OpenAI-compatible 协议；凭据只存引用，不进入事件或日志。
- 发送消息的 202 阶段只写 inbox；turn/start 与 user/message 同事务创建消息投影。
- 每会话单写者、短事务；模型流、SSE 发送和等待不能占数据库连接。
- 重启只补 interrupted，不自动重放付费调用或工具副作用。
- 不连接现有 data 用户库、不调用付费模型；使用临时数据库和本地协议服务器。
- 用户要求验收后删除测试文件；保留原 P0 架构门禁，不删除其 fixture。清理后再执行 clean verify，清楚区分删除前后的测试覆盖。
- 当前目录无 Git 元数据，不执行提交、分支或 worktree 操作。

## Review Focus

- 客户端断线或慢连接：持久 SSE 只按已提交 seq 回放，delta 无持久 id，队列溢出断开而不无限增长。
- 模型 finish 后 usage、截断或取消：不漏计用量，不把未完成响应当完成，不泄漏连接。
- 相同幂等键不同请求、并发发送/取消：重放响应一致，冲突明确拒绝，不重复执行模型。
- 实例锁、迁移、恢复与 shutdown 顺序：第二实例不能迁移；恢复完成前不服务；关闭前排空 Agent。
- 浏览器凭证边界：恶意 Host/Origin 拒绝，EventSource 可用 cookie，CORS 不对任意源开放。

---

### Task 1: 基线与文档对照

**Files:** 根 pom.xml、各模块 pom.xml、现有 src/test、docs/backend-implementation-plan.md。

**Interfaces:** 消费现有 Maven reactor 与 P1/P2 验收；产出实际可复现的构建命令和缺口清单。

- [x] 执行 `mvn clean verify -B`，记录所有失败、测试数与依赖问题。
- [x] 分别核验模型协议、聊天/SSE、安全/恢复，与现有文档对照；仅保留可构造失败场景的缺口。
- [x] 如基线失败，先定位原因；不以跳过测试或孤立编译冒充完整验收。

### Task 2: 补齐现有 P2 行为缺口

**Files:** 核验指出的 agent-core/src/main、plugins/model-providers/src/main 与 backend/src/main 对应实现；临时测试放对应模块 src/test。内核取消选项/turn 边界是必要的跨层修订，不在产品层重复竞态检查。

**Interfaces:** 沿用现有 LlmAdapter/LlmStream、ChatApplicationService、ChatAgentService、SseHub、RecoveryService 及现有 REST 契约，不引入新产品端点。

- [x] 为每项确认缺口增加真实行为回归：本地 HTTP 协议服务器、临时 SQLite、实际 Spring 装配；断言消费者可观察结果。
- [x] 逐项运行回归，观察预期失败后再修改生产实现。
- [x] 最小修改后运行同一回归及相关完整模块测试，检查取消/重放/恢复不会产生重复付费请求。
- [x] 独立复核跨边界行为，对确认的问题重复红绿测试循环。

### Task 3: 真实链路验收与契约同步

**Files:** contracts/http/openapi.yaml、contracts/sse 下现有事件目录、backend/README.md、README.md、docs/development.md、docs/database.md、docs/backend-implementation-plan.md。

**Interfaces:** 已实现端点、durable sessionId:seq / ephemeral delta、ProblemDetail 错误、启动配置。

- [x] 执行本地协议服务器端到端：配置创建、对话、HTTP 202、模型流、持久事件、SSE 重连、取消、幂等冲突、重启恢复。
- [x] 执行全模块 `mvn clean verify`；记录真实结果，区分协议兼容验收与真实外部模型验收。
- [x] 文档同步实际依赖版本、端点/认证、迁移、安全配置、P2 完成或阻塞状态；未验证内容不能标为完成。

### Task 4: 删除临时测试与最终干净验证

**Files:** 本轮及现有 P2 临时验收源码、dist 下已检查的测试探针/隔离编译/测试日志，所有 target 测试残留。

**Interfaces:** 保留 backend 原 P0 架构测试和 fixture、正式生产代码、正式契约与说明。

- [x] 检查待删文件清单，仅删除本次开发验收的临时测试/探针/产物，不删除用户数据。
- [x] 再执行 `mvn clean verify`，确认保留架构门禁通过且无已删除测试 class。
- [x] 更新进度与验收记录，明确删除后的 verify 不会重新执行临时功能案例；向用户报告产出、测试证据和剩余阶段。

## 执行结果（2026-11-03）

- 基线 38 项通过；六项原始缺陷和最终复核的预留归属/空闲写者保护两项回归均观察到预期失败，再修复通过。
- 最终删除前全模块 `mvn clean verify -B`：46 项通过（42 功能 + 4 原架构检查），零失败/错误/跳过。
- 按要求删除五个临时测试源码、dist 的测试探针/日志/隔离依赖和编译目录。dist 只保留 .gitkeep，未连接用户数据库或删除用户 data。
- 清理后全模块 `mvn clean verify -B`：4 项原架构检查通过，零失败/错误/跳过；测试源码与 target 清单中只剩原架构测试/fixture。后续 verify 不复现已删除的 42 项功能案例。
- 独立复核的 Important 已通过红绿回归和完整套件收敛，无延期 Minor；不再重复派发复核。
- 关键裁定、代价、真实外部模型与慢 socket 压力验收的未验证边界，完整归档到 [实施计划](../../backend-implementation-plan.md) §4。现有目录无 Git，不进行分支集成；清理本计划的 scratch ledger，保留本计划和生产文档。
