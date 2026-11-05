# P4 上下文、技能、记忆、搜索与 MCP 设计

> 状态：P4 核心已于 2026-11-04 实现并完成阶段验收。真实 MCP transport、翻译、语音、媒体适配器仍需外部配置；未配置时由 capabilities 明确报告 unavailable。临时测试按约定在阶段末删除。

## 目标与依据

P1–P3 已完成：内核闭环、持久化聊天、真实工具/审批/工作区。P4 把会话从“单轮文本 + 工具”扩展为可装配上下文、可渐进披露技能、可检索记忆、可使用搜索/MCP 的 Agent，并提供用量聚合。

依据：`docs/backend-architecture.md` §4.6、§8.4、§10.2、§10.3、§6.3、§11；实施顺序见 `docs/backend-implementation-plan.md` §6。

## 范围

### 目标

1. **上下文装配**：按稳定顺序拼接提示词 section；首个结果写入 `system/message` surface，后续变化按 `SystemPromptUpdatePolicy` 替换或追加；提示词是数据，不是代码。
2. **Token 计量与压缩**：估算 + 实际 usage 归一化；超过阈值时生成摘要并只改 surface，原始事件永不删除；未配对压缩在重启后可重试/放弃。
3. **技能渐进披露**：系统提示词只放技能 name + description；模型通过 `skill` 工具按需加载正文；技能正文不能覆盖系统提示词或审批策略。
4. **记忆**：用户可编辑 Markdown + FTS5 检索；异步提取；记忆片段作为独立 section 注入，可追溯来源。
5. **搜索 / MCP / 翻译 / 语音 / 媒体**：通过核心端口与插件适配器接入；未配置时不伪装成功；MCP 工具名 `mcp__<server>__<tool>`。
6. **用量聚合**：按会话、模型、日期聚合 token/成本和缓存命中，提供只读查询。

### 非目标

- P5 编排（Ticket/Worker/effects/outbox/watchdog）。
- 真实 PTY、OS 沙箱、非 Windows 本地运行时（沿用 P3 边界）。
- 附件上传、前端完整 UI；P4 先交付后端/内核与契约。
- 无配置的外部搜索/翻译/语音/媒体/MCP 服务不伪造成功。

## 关键决策

### D1：P4 拆成五个可独立验收的切片

| 切片 | 交付 | 依赖 |
| --- | --- | --- |
| P4-A | 上下文装配、TokenMeter、压缩策略/执行、系统提示词 surface | P1–P3 |
| P4-B | 技能 manifest、`SkillCatalog`、渐进披露、`skill` 工具、提示词 | P4-A |
| P4-C | Markdown 记忆、FTS5、检索 section、异步提取、CRUD | P4-A |
| P4-D | 用量聚合与只读查询 | P2 |
| P4-E | search/MCP/translation/speech/media 端口与适配器 | P4-A/B |

每个切片独立跑测试、独立清理临时测试；P4 整体退出前做一次全量验收。

### D2：提示词装配

- 核心 `PromptSection(name, order, content)` 与 `SystemPromptAssembler`：
  - 按 `order` 升序；同 `order` 按 `name` 排序；同名 section 直接失败。
  - 空内容 section 被忽略；渲染结果保留 section 边界注释，便于调试但不注入模型额外指令。
- 后端 `PromptSectionProvider` 从 `prompts/system/*.md` 读取只读模板，按 profile 组合：
  - `00-identity.md`、`10-environment.md`、`20-tools.md`、`30-skills.md`、`40-memory.md`、`90-guardrails.md`。
  - assistant 的 `systemPrompt` 作为独立 section，order 由 assistant 配置决定（默认 50）。
- 首个渲染结果在会话创建时写入 `system/message` surface；后续变化（技能/记忆/环境变化）由写者提交 `SystemMessageEvent` + `SurfaceReplace`，策略来自 `ContextPolicy.systemPromptUpdate()`。
- `REPLACE_IN_PLACE`：追加 surface replace 遮蔽旧系统消息；`APPEND_TO_HISTORY`：保留旧版本，新版本作为新节点。
- 提示词模板占位符只允许白名单简单替换；禁止 SpEL/脚本/任意表达式。

### D3：压缩

- 复用现有 `CompactionStartEvent` / `CompactionSummaryEvent` / `CompactionEndEvent`。
- `CompactionEndEvent` 增加可空 `error` 字段，保留 1 参构造器兼容旧事件；旧 JSON 缺字段解码为 null。
- `CompactionPlanner`：
  - 输入派生消息、`ContextPolicy`、`TokenMeter`、当前 seq；
  - 只在估算 token 超过 `compactionThresholdTokens` 时触发；
  - 保留最近 `keepRecentTokens`，且不拆散 `tool/call`–`tool/result` 配对；
  - 输出 `CompactionPlan(shadowedStartSeq, shadowedEndSeq, shadowedSeqs, messagesToSummarize)`。
- 摘要调用使用 `LlmAdapter`，`purpose=compaction`，失败不删除原文；`CompactionEndEvent.error` 记录失败。
- 成功后追加 `compaction/summary` 和一条带 `SurfaceReplace` 的 `user/message`（摘要文本），原文事件保留。
- 自动压缩在 step 前触发；手动入口 `POST /api/v1/sessions/{id}/compaction` 与 `POST /api/v1/sessions/{id}/compaction/{seq}/abandon`（idle 才允许）。
- 未配对 `compaction/start` 在恢复时标记 `interrupted`，阻塞新的自动压缩直到手动重试/放弃。

### D4：技能

- 核心：`SkillId`（已有）、`SkillSummary(id,name,description,whenToUse,allowedTools,version)`、`Skill`（含正文与资源）、`SkillSource` 端口、`SkillSelection`。
- 后端 `SkillCatalog`：
  - 读取 `skills/manifest.json`、`skills/builtin/<name>/SKILL.md`、`data/skills/<name>/SKILL.md`；
  - frontmatter 严格校验 name/description/when-to-use/allowed-tools/version；未知字段失败；
  - 用户安装的技能只读加载，编辑/删除通过后端 API；
  - 技能正文按需加载，系统提示词只含 name + description。
- `skill` 工具：`{"name": "<id>"}` → 返回正文与资源清单；未知技能结构化错误；正文不可执行、不可改策略。
- 技能选择来源：assistant 配置的 `skills` 列表；空表示不暴露技能。每 turn 冻结。

### D5：记忆

- `data/memory/*.md` 为用户可编辑事实；FTS5 表 `memory_documents` / `memory_fts`（V9）保存索引与元数据，正文仍以文件为准。
- 核心 `MemoryStore` 端口：`search(query, limit)`、`read(id)`、`upsert/delete`（后端实现）。
- 检索 section：按当前用户输入做 FTS5 查询，最多 N 条/字节；每条带文档 id 与标题，不把整库注入。
- 异步提取：turn 结束后用 `purpose=memory_extract` 的模型调用生成候选事实，写入待确认队列或直接更新 Markdown（v1 采用“写入 data/memory/inbox/ 待用户确认”，避免模型静默改事实）。
- 用户编辑 API：列表/读取/保存/删除；保存后重建 FTS5 索引。
- 记忆正文是不可信内容，不能覆盖系统提示词或审批策略。

### D6：搜索与 MCP

- 核心端口：`SearchPort`、`McpPort`（已列入架构）。
- `search-providers`：先实现 `SearxngAdapter`（HTTP JSON）与 `TavilyAdapter`/`BraveAdapter` 的配置骨架；结果统一为 `SearchResult(title,url,snippet,score)`，截断与引用 id。
- `mcp-client`：
  - stdio 与 HTTP SSE 传输；
  - 工具名强制 `mcp__<server>__<tool>`，server/tool 名做字符白名单；
  - 连接生命周期由 backend `McpServerService` 管理，不进入 AgentScope 的关闭语义；
  - 未配置/连接失败返回结构化错误，不伪造工具。
- `translation`/`speech`/`media`：先落端口与 OpenAI-compatible/本地配置适配器；没有凭据时明确 unavailable，不阻塞聊天。

### D7：用量

- `usage` 后端聚合查询：按 session/model/day 聚合 input/output/cache/reasoning token 与 cost_micros；只读 API；不修改 `usage_records`。
- 成本使用 provider/model 的配置单价或显式 cost_micros；缺失单价时返回 token 聚合与 null 成本，不伪造金额。

## 验收与清理

- 每个切片 TDD：先写失败测试，再实现；测试使用临时 SQLite/临时目录/本地 fixture。
- P4 整体退出：
  - 压缩后原文仍可从事件日志重建；
  - 技能正文不能覆盖系统提示词或审批策略；
  - MCP 工具名符合 `mcp__<server>__<tool>`；
  - 未配置外部能力明确 unavailable；
  - 全模块 `mvn clean verify` 通过；最终可执行 JAR 检查；真实外部 provider 验收只在有配置时执行。
- 按项目约定，阶段验收后删除本阶段新增临时测试/runner/隔离数据，再做干净构建；保留原 P0 架构测试与 fixture。
- P4 收敛后进入 P5；P5 默认 observe，不自动接单。

## 假设与边界

- 用户未提供额外 P4 产品选择；本设计采用架构文档默认值与 P2/P3 既有约定。
- 前端 UI 不在本阶段验收；后端契约与 API 先行。
- 搜索/MCP/翻译/语音/媒体真实外部验收取决于用户配置；没有配置时只做本地 fixture 与显式 unavailable。
- 提示词与技能正文按不可信数据处理；任何技能都不能改变审批策略或工具权限。
