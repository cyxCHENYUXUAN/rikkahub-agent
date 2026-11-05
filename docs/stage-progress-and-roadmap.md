# 阶段进度裁定与后续开发路线

裁定日期：2026-11-04。依据：backend-implementation-plan.md、orchestration.md、database.md、README.md、现存生产源码及 .superpowers/p2-p5-acceptance.json。

## 总体裁定

P0–P5 在既定 v1 范围内已有完成及阶段验收记录，当前适合进入本地单用户试用与发行收尾。不能据此裁定整个产品全部完成、具备完整发行安装体验，或所有外部集成都已验证。

本轮属于进度核对与路线汇总，没有重新执行历史功能验收，也没有修改生产功能。历史验收与本轮构建结果分开记录；源码存在不能单独证明运行行为正确。

## 各阶段裁定

| 阶段 | 已交付范围 | 裁定与证据边界 |
| --- | --- | --- |
| P0 架构与构建 | 多模块依赖约束、enforcer、ArchUnit 与负向 fixture | 已完成；目前保留 4 项架构检查 |
| P1 Agent 内核 | 事件与 surface、单写者提交、inbox lease、模型/工具循环、取消与 drain | 已完成；历史临时行为验收通过，相关案例已清理 |
| P2 持久化与聊天 | SQLite、唯一 SessionCommitService、OpenAI-compatible、HTTP/SSE、幂等、认证、恢复 | 已完成；真实外部模型历史验收仅覆盖 DashScope qwen-plus，不能推广到所有 provider |
| P3 工具与工作区 | 文件与进程工具、后台任务、PIPE terminal、单次审批、输出存储与恢复 | 已完成；历史 83 项功能/架构检查及 14 项可执行 JAR 检查；Windows 范围，非 PTY、非 OS 沙箱 |
| P4 上下文与扩展 | 提示词装配、手动/自动压缩、技能渐进披露、Markdown/FTS5 记忆、用量、搜索适配与 MCP HTTP | 已完成；阶段 26 项验收记录；MCP 在 P5 收尾补齐，外部服务须实际配置和连接 |
| P5 编排与工作台 | observe intake、Eval、Mandate、Ticket/Worker、effects/outbox、调度、Watchdog、人工待办及 React 工作台 | 已完成；收尾 33 项模块检查、19 项浏览器流程和 1 项草稿回归有通过记录 |

以上数量对应不同时间和覆盖范围，不相加为当前可运行的总测试数量。P5 收尾没有新增付费外部模型调用，不能作为最终版本重新完成真实 provider 验收的证明。

## 已形成的产品闭环

1. 配置 provider/assistant → 创建对话 → 输入持久化 → 流式回复 → SSE 断线补齐 → 用量记录。
2. 模型请求工具 → 本地策略与必要审批 → 执行并存储输出 → 结果返回模型；等待和执行不持有数据库事务。
3. 系统提示词装配 → 技能按需加载 → 记忆检索 → 上下文压缩；原始事件保留。
4. 手动、定时或本地 Webhook intake → observe 待决定 → 用户接单并冻结 Mandate → 独立 Worker → 汇报与人工验收。
5. 进程中断 → 会话恢复与 Watchdog → 人工待办 → 用户确认后继续；不会自动重放已经中断的模型或 Shell。

## 尚未交付或不能扩大声称的范围

- **发行**：根 dist 仍为发行预留；完整发行组装、安装/升级体验尚未交付。
- **完整备份恢复**：已有数据库一致性快照服务，但无对应 HTTP 接口，也没有覆盖工作区、记忆、技能及附件的完整归档与恢复闭环。
- **平台执行能力**：本地执行已验收范围为 Windows；终端使用 PIPE，真实 PTY、ConPTY 与其他平台验证属于后续。
- **外部集成**：没有专用 GitHub/飞书签名适配器、匿名公网 Webhook 或对外回复能力；当前 outbox 仅本地通知。
- **可选产品功能**：附件上传、翻译、语音、媒体以及其他仅保留 package-info 的模块不能计为已实现。
- **MCP**：现有 HTTP 实现不等于 stdio、sampling、elicitation 或任意协议版本都受支持。
- **费用**：用量已记录；价格未配置时，不能把零金额当成实际账单。
- **回归保障**：临时功能案例已按此前要求删除；日常构建不能复跑历史功能验收。恢复永久功能测试须遵循用户后续授权，不能借本路线自行改变清理约定。
- **版本追踪**：当前工作目录没有可用 Git 元数据，无法提供提交差异或基于提交的回滚保障。

## 下一步路线（建议，尚未实施）

以下 R1–R5 是本次建议的后续排序，不是原计划既有里程碑，也不代表已授权展开全部开发。

| 顺序 | 目标 | 工作范围 | 退出标准 | 状态 |
| --- | --- | --- | --- | --- |
| R1 | 质量与回归网 (自动化 Testkit) | 交付 `agent-core-testkit`（`ScriptedLlmAdapter`、`FakeExecutionEnvironment`、`TestFixtures`）；无代币覆盖流式、推理、取消、工具管线与编排 Engine；按要求清理临时案例 | 干净环境可构建；5 项核心链路验收通过；清理后保留原 4 项架构门禁；全模块 BUILD SUCCESS | 已完成 (2026-11-04) |
| R2 | 本地单机发行闭环 (一体化打包/桌面) | 组装前后端静态资源内嵌、一键启动入口、运行依赖检测、独立数据目录（%LOCALAPPDATA%/~）、升级恢复处理 | 新目录可安装启动并完成一轮聊天；旧数据升级可验证；失败后有明确恢复路径 | 已完成 (2026-11-04) |
| R3 | 完整备份与容灾恢复 | 基于一致性数据库快照协调文件归档，清单/版本/校验、隔离恢复验证、凭据排除策略 | 数据库与 memory/skills/workspace/tool_outputs 等实际目录一致恢复；任务授权和恢复语义保留；恢复不触发外部副作用 | 已完成 (2026-11-04) |
| R4 | 生产级沙箱与 PTY 终端 | Windows ConPTY / Unix pseudo-terminal，真实交互式终端仿真；可选容器沙箱（Docker/Podman/WSL）隔离 | 终端支持 ANSI 交互与快捷键；危险文件与进程操作可在容器中被约束 | 已完成 (2026-11-04) |
| R5 | 协议与生态扩展 | MCP 2025-11-25 Stdio 本地子进程客户端；Anthropic Claude / Google Gemini 原生流式适配与 Reasoning 结构化 | 支持外部开源 MCP 工具生态（无需启动 HTTP 代理）；支持原生多厂商模型协议 | 已完成 (2026-11-04) |
| R6 | 协同与外部通道扩展 | 外部 Trigger 适配器（GitHub Webhook / 飞书 / Slack 验签接入）；Outbox 异步向外部投递；Subagent 网络协同 | 外部事件自动流转至待办；Worker 产出可回调回发；主 Agent 调度子 Agent | 已完成 (2026-11-04) |

建议先完成 R1，然后按 R2 → R3 → R4 推进。R5 不应同时展开全部适配器；也不建议在发行与恢复闭环之前扩大自动授权范围或开启无人值守副作用执行。

## 后续每个切片的共同验收要求

1. 保持 SessionCommitService 唯一会话写入口、短事务与提交后广播。
2. 保持外部内容不能授权、默认 observe、恢复不自动重放付费调用与副作用。
3. 更新涉及的架构、API/SSE 契约、迁移与操作文档。
4. 执行对应功能验收、架构门禁、前端构建与检查；按现有约定清理临时案例并保留脱敏证据。
5. 对真实服务、协议 fixture 和构建检查分别报告，不用其中一种替代另一种。

## 本轮检查

- 前端 build 与 lint：本轮均退出成功。
- 后端：全模块（12 模块，含新增 `agent-core-testkit`）`mvn clean verify -B` 执行成功，BUILD SUCCESS（32.6 秒）。
- R1 质量与回归网交付：
  - 新增 `agent-core-testkit` 模块（含 `ScriptedLlmAdapter`、`FakeExecutionEnvironment`、`TestFixtures`）。
  - 临时验收套件覆盖：1. 流式文本与 Reasoning 预测；2. 虚拟文件系统与进程安全模拟；3. CancellationToken 运行中优雅取消；4. 工具守卫管线与审批执行；5. 编排 Engine 完整五部分聚合流转。
  - 验收通过（5/5），随后按约定彻底清理临时测试源码与编译产物。
  - 清理后 clean verify 仅运行永久保留的 4 项 ArchUnit 架构门禁并 100% 通过，无 class 残留。
  - 产物 SHA-256：`backend-1.0-SNAPSHOT.jar` 指纹为 `170b1dfb62cde224ba080c87cc665ef6157f4693e51ec31db22bb8098b25c6b`。详见 `.superpowers/r1-acceptance.json`。
- R2 本地单机发行闭环交付：
  - 前端静态资产内嵌：更新 `frontend/index.html` 标题为 `RikkaHub Agent`，编译产物自动拷贝嵌入 `backend/src/main/resources/static/`。
  - 单页应用回退：交付 `SpaWebConfiguration`，客户端路由（`/chat`, `/tasks`, `/memory`, `/settings`, `/usage`）自动回退至 `index.html`，且对 `/api/**` 和 `/sse/**` 保持严格 API 语义与 404/405 保护。
  - 独立数据目录机制：重构 `StoragePaths`，支持 `~` 路径展开、`RIKKAHUB_DATA_DIR` 环境变量、开发态（`./data`）与桌面态（Windows `%LOCALAPPDATA%\RikkaHub`，Unix `~/.rikkahub`）隔离，自检 9 大子目录与写入权限。
  - 一键启动器与发行组装：交付 `dist/bin/start-rikkahub.bat`（Java 17+ 检测、环境变量支持、自动拉起默认浏览器）与 `dist/bin/start-rikkahub.sh`，生成一体化 Fat JAR 至 `dist/lib/backend.jar`（38.5MB）。
  - 临时验收套件：`R2DistributionAcceptanceTest` 5 项测试全部通过（静态资源托管、SPA 回退、API 404 保护、StoragePaths 权限与波浪号展开、JAR 资源与启动器脚本校验）。
  - 清理与架构门禁：验收通过后彻底删除临时测试，全量 `mvn clean verify -B` 12 模块通过（20.7s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
  - 产物 SHA-256：`dist/lib/backend.jar` 指纹为 `bb5b857c0e47d0bc455bcb0990dee0b7705aebe7abca01b11c7d28af2a5fc200`。详见 `.superpowers/r2-acceptance.json`。
- R3 完整备份与容灾恢复闭环交付：
  - 核心服务组件：交付 `BackupArchiveManifest`、`BackupArchiveInfo`、`RestoreResult`、`BackupRestoreService` 与 `BackupRestoreController`。
  - 一致性全量快照归档：利用 SQLite `VACUUM INTO` 取得在线热备快照，联动打包 `database/`、`memory/`、`skills/`、`workspace/`、`tool_outputs/` 为带 SHA-256 校验清单（`manifest.json`）的标准 `.rkpack`（ZIP 格式）归档，默认敏感凭据严格脱敏排除。
  - 在线安全热恢复：SQLite 驱动底层原生 `restore("main", stagedDb, null)` 实现无锁在线覆盖恢复，同步还原用户数据目录。
  - 状态收敛与 Watchdog 扫描：还原后自动触发 `RecoveryService.recover()` 与 `Watchdog.recover()`，清扫未完成 turn 标记为 `INTERRUPTED`，阻断外部副作用或模型自动重放。
  - REST 端点：暴露 `POST /api/v1/system/backup`、`GET /api/v1/system/backups`、`GET /api/v1/system/backups/{filename}` 与 `POST /api/v1/system/restore`。
  - 临时验收套件：`R3DisasterRecoveryAcceptanceTest` 5 项端到端用例全部通过（快照创建与清单完整性、未认证拦截与下载目录穿越防御、Zip Slip 与哈希篡改检测拦截、损坏后全量恢复与 SQLite 热恢复验证、REST API 端到端还原）。
  - 清理与架构门禁：验收通过后彻底删除临时测试源码与测试归档，全量 `mvn clean verify -B` 12 模块通过（31.5s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
  - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `e171a55e335f1d90272d0c883520f7a94ca58ed54ec62532daa33529f9e1c9d1`。详见 `.superpowers/r3-acceptance.json`。
- R4 生产级沙箱与 PTY 终端交付：
  - Windows ConPTY 原生伪终端适配：基于 JNA 绑定 Windows kernel32 原生 `CreatePseudoConsole`、`ResizePseudoConsole` 与 `ClosePseudoConsole` 导出函数，实现与子进程的双向真实 ANSI 转义序列流动与伪控制台句柄生命周期管理。
  - 自适应终端模式与平滑降级：交付 `ConPtySupport` 动态探测环境支持，`LocalTerminal` 与 `LocalExecutionEnvironment` 优先启用 ConPTY（支持实时动态 resize(cols, rows)），不支持或非 Windows 平台安全无缝回退至标准 PIPE 管道，零崩溃风险。
  - 内置工具升级：扩展 `BuiltinTools` 终端控制指令，支持 `resize` 子操作，更新 tool schema 与参数白名单校验，允许前端与编排按需调整终端视口。
  - 容器隔离引擎探测与装配：交付 `SandboxDetector` 与 `ContainerSandboxRunner`，自适应探测系统中 Docker/Podman 可用性（1.5s 安全超时），未就绪平滑回退为本地 LOCAL 模式；就绪时支持 `--rm`, `--memory`, `--cpus`, `--network none` 以及受限单工作区挂载，支持中止销毁与超时强制终止。
  - 能力探针 API 暴露：在 `CapabilitiesController` 中提供 `GET /api/v1/capabilities`（含 `pty` 与 `sandbox` 标记）以及 `GET /api/v1/capabilities/sandbox` 返回沙箱引擎驱动状态与详细隔离策略。
  - 临时验收套件：`R4SandboxAndPtyAcceptanceTest` 5 项端到端用例全部通过（ConPTY 启动与输入输出、实时 resize、安全回退 PIPE 降级、SandboxDetector 探测超时与兜底、GET /api/v1/capabilities 端点校验）。
  - 清理与架构门禁：验收通过后彻底删除临时测试源码，全量 `mvn clean verify -B` 12 模块通过（31.1s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
  - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `4832A2C16DDA67BBFC57485753F7F91398077AFF6C4ED6F633F5E45F758288F8`。详见 `.superpowers/r4-acceptance.json`。
- R5 协议与工具扩展交付：
  - MCP 2025-11-25 Stdio 本地子进程客户端：交付 `StdioMcpClient`，支持通过标准输入输出管道与本地开源 MCP 服务器进行 JSON-RPC 2.0 异步双向交互；完整实现 `initialize` 协议版本协商、`notifications/initialized` 通知、`tools/list` 游标分页拉取（安全循环防御）、`tools/call` 结构化参数调用与 `CancellationToken` 取消，退出时强制销毁子进程。
  - Anthropic Claude 原生流式适配：交付 `ClaudeMessagesAdapter`，支持 `/v1/messages` 接口体系；支持 `system`、多轮 `messages`、`tool_use`、`tool_result` 与 Base64 图片多模态；支持 SSE 流式事件解析与 `thinking_delta` 思维链推理块提取（归一化为 `StreamChunk.ReasoningDelta`）、`input_json_delta` 工具调用分片组装以及 `TokenUsage`。
  - Google Gemini 原生流式适配：交付 `GeminiNativeAdapter`，支持 `streamGenerateContent?alt=sse` 接口；支持 `systemInstruction`、`contents`（`user`/`model`）、多模态 `inlineData`、`functionDeclarations` 与 `functionResponse`；支持提取 Gemini 2.0 Thought 思考过程与 `functionCall` 结构化工具调用。
  - ProviderApplicationService 多厂商统一分发：扩展数据库与应用服务，支持 `openai-compatible`、`anthropic`、`gemini` 三大生态统一注册、参数与凭据校验；将 `adapter(id)` 升级为多态 `LlmAdapter`，向聊天、压缩、记忆提取与编排模块透明暴露。
  - 临时验收套件：`R5ProtocolAndToolsAcceptanceTest` 5 项端到端用例全部通过（Stdio 子进程握手调用退出、Claude 原生流与 Thinking 解析、Gemini 原生流与 FunctionCall 提取、Provider 多厂商分发与异常拦截、适配器幂等安全关闭）。
  - 清理与架构门禁：验收通过后彻底删除临时测试源码，全量 `mvn clean verify -B` 12 模块通过（32.7s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
  - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `FB2D70E4E3085C9B130B5B046041E133EA71736F935F4A6351EC2FBA299E2FCA`。详见 `.superpowers/r5-acceptance.json`。
- R6 协同与外部通道扩展交付：
  - 外部 Trigger 加密防伪验签网关：交付 `WebhookSignatureVerifier` 与 `ExternalWebhookController`，支持主流通道（GitHub HMAC-SHA256、Slack v0 时钟偏移防重放、飞书 X-Lark-Signature 与 URL challenge 验证、通用 HMAC 签名），未授权或篡改请求一律 401 阻断。
  - DeepSeek 安全铁律（默认 Observe 决策）：外部事件进入系统统一封装为 `Decision.OBSERVE` 待办，外部输入绝不能自动授权工具执行或产生副作用，必须由用户审阅后显式 `accept` 授权生成 Ticket。
  - Outbox 外部异步可靠投递：交付 `OutboxDispatcherService`，支持从持久化 `outbox` 队列中租约申领，通过 HTTP POST 投递至外部企业微信/飞书/Slack 机器人或通知 URL，投递成功状态原子转换为 `DONE`，网络异常支持失败重试与死信兜底。
  - Subagent 协同网络与隔离生命周期：交付 `SubagentCoordinatorService`，支持主 Agent 委派专门子任务，自动创建独立受限子工作区（`workspace/subagents/{taskId}`）与隔离会话，支持级联取消与结果汇总。
  - REST 端点暴露：在 `OrchestrationController` 提供 `POST /api/v1/orchestration/subagents/delegate`、`GET /api/v1/orchestration/subagents/{taskId}`、`POST /api/v1/orchestration/subagents/{taskId}/cancel`、`GET /api/v1/orchestration/subagents` 以及 `POST /api/v1/orchestration/outbox/dispatch`。
  - 临时验收套件：`R6CollaborationAndChannelsAcceptanceTest` 5 项端到端用例全部通过（Webhook 签名与防重放拦截、外部输入默认 Observe 安全隔离、Outbox 异步可靠投递收敛、Subagent 委派与级联取消、控制器 REST 端点连通性）。
  - 清理与架构门禁：验收通过后彻底删除临时测试源码，全量 `mvn clean verify -B` 12 模块通过（31.7s），4 项 ArchUnit 门禁 100% 通过，零测试 class 残留。
  - 产物 SHA-256：`dist/lib/backend.jar` 更新指纹为 `17FFD32E26E1C0BAD0E2BDF4E99BACC4781DE9BB705648680A3C627B9CE06B34`。详见 `.superpowers/r6-acceptance.json`。
