# Java 后端

Java 17 / Spring Boot 4.1.2，多模块 Maven 工程。JSON 使用 Jackson 3；插件和内核不依赖 Spring。

## 启动与构建

需要 Java 17+、Maven 3.9+；自动重启启动器另需 Node.js 22.12+。

在项目根目录执行：

```powershell
npm run dev:backend
```

也可在 backend 目录执行 `.\dev.ps1`。启动器在 Java 源码、资源、配置或 pom 变化后重新构建并重启；Ctrl+C 只停止它创建的进程树。开发重启的 `-DskipTests` 不是验收。

普通启动与可执行 JAR：

```powershell
mvn install -DskipTests
mvn -pl backend spring-boot:run
# 或：
mvn package -DskipTests
java -jar backend/target/backend-1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8081`，`SERVER_PORT` 可改端口；根路径无业务页面，返回 404。数据目录默认是项目 data，可用 `RIKKAHUB_DATA_DIR` 更换。不要把服务暴露到局域网或公网。

## 已实现的 P2/P3 接口

所有路径前缀为 `/api/v1`，完整 DTO、状态码和限制以 [OpenAPI](../contracts/http/openapi.yaml) 为准。

| 接口 | 用途 |
| --- | --- |
| `GET /setup/session` | 为受信浏览器建立 HttpOnly / SameSite=Strict cookie |
| `POST/GET /providers` | 创建、列出 OpenAI-compatible provider；不返回密钥值 |
| `POST/GET /assistants` | 创建、列出文本 assistant |
| `GET /models` | 已配置 assistant 引用的模型，不是远端模型发现 |
| `POST/GET /conversations` | 创建对话与 session、列出对话 |
| `GET/PATCH/DELETE /conversations/{id}` | 查询、带 metadataVersion 改名或设置会话级工具/审批策略覆盖、软归档 |
| `POST/GET /conversations/{id}/messages` | 接受文本输入、查询消息投影 |
| `POST /conversations/{id}/turns/{turn}/cancel` | 取消指定 turn，不误取消下一轮 |
| `GET /conversations/{id}/stream` | SSE 实时事件与持久回放 |
| `GET /sessions/{id}/events` | 按 seq 分页读取权威事件日志 |
| `GET /approvals?sessionId=` | 列出会话的持久审批投影（最多 100 条） |
| `POST /approvals/{id}` | 记录单次决定，返回 202；决定不等于已结算 |
| `GET /sessions/{id}/tool-outputs/{reference}` | 读取持久工具输出的字节范围 |
| `POST /sessions/{id}/compaction` | 对 idle 会话手动压缩；`POST .../{seq}/abandon` 放弃未配对压缩 |
| `GET/PUT/DELETE /memories[/{id}]` | 记忆文档列表、读取、保存、删除 |
| `GET /memories/search`、`POST /memories/refresh`、`POST /memories/extract` | FTS5 检索、重建索引、提取待确认候选 |
| `GET /usage`、`GET /usage/by-model`、`GET /usage/by-day` | token/成本聚合 |
| `GET /capabilities` | 报告配置门控的 search/MCP/翻译/语音/媒体可用性 |

所有写接口要求 `Idempotency-Key`：相同 scope/key/body 重放保存响应，不同 body 返回 409。发送返回 202 只表示 inbox 与幂等响应已提交；不是模型执行成功，也不直接创建用户消息投影。会话执行容量为 64（含正在激活的预留，同一会话不重复计数）；每个请求持有独立预留令牌，只释放自己的名额，等待激活的空闲写者不能被淘汰。满载时在接受新输入前返回 503，可用原 key/body 重试。

归档与延迟激活共用生命周期边界：归档后旧的响应完成回调不能创建新写者。归档取消当前执行，再在数据库事务和全局锁之外排空并释放写者容量；未认领输入保留但不自动执行。原幂等请求的重放可完成尚未成功的写者退休；无法确认排空时返回 `RECOVERY_REQUIRED`，不释放仍可能运行的写者。

SSE 只有 `session.committed` 带持久 id（`sessionId:seq`）。delta 等临时事件不带 id、不回放；最终状态从持久事件或消息查询恢复。首次可传 `after`，自动重连的合法 `Last-Event-ID` 优先于初始 query。每连接队列 256，最多 32 个连接，15 秒心跳、120 秒连接超时；慢连接关闭后应重连。见 [事件目录](../contracts/sse/events.yaml)。

## 配置一个模型并发消息

以下 PowerShell 示例面向本机命令行联调。先在启动服务的终端设置所选 provider 的环境变量，或在忽略版本控制的 `config/application.local.properties` 中配置同名属性；不要把真实 key 写进示例、数据库、请求体或 URL。

```powershell
$base = 'http://127.0.0.1:8081/api/v1'
$token = (Get-Content -Raw 'data/config/local-token').Trim()
function Send-LocalJson($path, $body) {
    $headers = @{ 'X-RikkaHub-Token' = $token; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    Invoke-RestMethod -Uri ($base + $path) -Method Post -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ($body | ConvertTo-Json -Depth 8)
}
# baseUrl 填所选服务的 API 根地址（例如含 /v1）；不要填 chat/completions 路径。
$provider = Send-LocalJson '/providers' @{
    name = 'My provider'; baseUrl = 'https://your-provider.example/v1'; credentialRef = 'env:MY_MODEL_API_KEY'
}
$assistant = Send-LocalJson '/assistants' @{
    name = 'My assistant'; providerId = $provider.id; model = 'your-model-id'; systemPrompt = '请简洁回答。'
}
$conversation = Send-LocalJson '/conversations' @{ assistantId = $assistant.id }
$accepted = Send-LocalJson ('/conversations/' + $conversation.id + '/messages') @{
    content = @(@{ type = 'text'; text = '你好' })
}
# 读取最终消息，或用 SSE 订阅执行过程。
Invoke-RestMethod -Uri ($base + '/conversations/' + $conversation.id + '/messages') `
    -Headers @{ 'X-RikkaHub-Token' = $token }
```

示例 helper 为新操作生成新 key；遇到响应不确定、超时或 503 后，应保存并重用原 key 和原 body，不能直接重新运行 helper 生成新 key。

Provider 只保存 `credentialRef`，当前格式为 `env:大写环境变量名`。适配器使用 HttpClient 异步发起请求，专用有界 I/O 池解析流（每适配器最多 16 条活跃流，不是全程非阻塞），整体超时默认两分钟；不在模型流期间占数据库连接。

## 浏览器认证与开发跨域

同源页面先 `GET /api/v1/setup/session`，之后通过 cookie 调用接口和原生 EventSource，不要求 SSE 设置自定义 header。命令行使用私有 `data/config/local-token` 的 `X-RikkaHub-Token` header；不得把 token 放 query。

前端 Vite 跨域联调需在私有配置显式设置 `rikkahub.security.development-cors=true`，仅允许 `http://localhost:5173`。前端与后端 URL 应统一使用 `localhost`，请求使用 `credentials: 'include'`，跨源 EventSource 使用 `withCredentials: true`；不要混用 localhost 与 127.0.0.1 导致 SameSite cookie 不发送。生产默认不开跨域。

Host 必须是本机允许的 authority 且端口匹配，外来 Origin 拒绝。此认证基线防御恶意网页/跨站请求，不隔离能伪造 HTTP 头的恶意本地 OS 进程或其他本机账户；需要该隔离时不能依赖浏览器 bootstrap 或 token 文件权限替代系统级认证。

## 恢复与验收范围

启动顺序为实例锁 → Flyway → 恢复 → 接请求。恢复配对未完成的审批/工具/step，写 interrupted，不重放付费模型或工具调用。过期 inbox lease 达到 `rikkahub.recovery.max-inbox-attempts`（默认 3）后取消，启动和运行期使用相同阈值。优雅关闭先停止接单并以 Shutdown 取消/排空 Agent，再关闭 SSE 与数据库，保留未认领输入。

P2 生产主链路已实现，本地协议服务器与临时 SQLite 可验证真实 HTTP → 202 → 协议适配器 → 持久事件/SSE。2026-11-04 已按用户明确授权，用环境中的 `DASHSCOPE_API_KEY`、DashScope `qwen-plus` 和隔离临时数据完成一次最小真实外部模型验收：202 → SSE 实时 delta → 持久 completed turn / 消息 / usage；密钥未进入日志。该次验收只覆盖该 provider/model 的协议兼容，不代表其他 provider 差异已验证。

P3 工作区能力已接入生产：每个会话写者拥有独立本地执行环境（Windows Job Object 进程归属、有界双流捕获、超限落盘），注册 7 个内置工具 `read/write/edit/glob/grep/shell/terminal`。权限模型：`toolPermissionMode`（READ_ONLY / WORKSPACE_WRITE / FULL_ACCESS）+ `approvalPolicy`（ASK / NEVER）+ 每工具规则；assistant 提供默认，`PATCH /conversations/{id}` 可设置会话级覆盖（null 表示继承）。有效策略在每 turn 开始时解析并冻结：会话覆盖 > assistant > 安全默认，修改从下一 turn 生效。默认 WORKSPACE_WRITE：文件读写直接允许，shell/terminal 在 ASK 策略下需单次审批；READ_ONLY 是硬上限，显式 ALLOW 规则不能放开写入或任意执行。shell/terminal 使用宿主账号的全部权限执行任意代码——这不是 OS 沙箱，cwd 内也不构成沙箱边界。terminal 是明确标识的 PIPE 通道，不支持 resize。决定 API 只记录意图（202），状态仅由写者的 `approval/decided` 事件结算；重启后未结算审批一律不可用，不重放副作用。超限工具输出以持久引用落盘 `data/tool_outputs`，按会话配额收取，重启后可继续读取，跨会话引用与缺失引用不可区分。附件上传与上下文压缩留给 P4，不提供伪成功实现。

P3 于 2026-11-04 完成阶段验收。P4 随后完成：系统提示词按 section 装配并写入 `system/message`；上下文超过阈值时自动摘要，支持手动压缩与放弃，压缩只改 surface、原文事件保留；技能只把 name+description 放入系统提示词，`skill` 工具按需加载正文；记忆正文保存在 `data/memory/*.md`，FTS5 索引（V9）用于 `memory_search`，提取候选写入 `data/memory/inbox`；usage 提供按会话/模型/日期聚合；搜索走 SearXNG 适配器并由 `rikkahub.search.searxng-url` 配置，未配置时 `web_search` 明确失败；MCP 工具名固定为 `mcp__<server>__<tool>`，真实 MCP server/翻译/语音/媒体适配器未配置时在 `GET /capabilities` 中为 false。可选 `rikkahub.memory.extract-on-turn=true` 在 turn 结束后异步提取，默认关闭以避免未授权的付费调用。

全模块验收命令为 `mvn clean verify`。用户要求验收后删除临时功能测试；P3 的 10 个临时测试与 4 个 runner、P4 的 9 个临时测试与临时 OpenAPI 脚本均已删除，清理后的 verify 只执行实际保留的原 P0 架构检查（4/4），不能复现已删除的行为案例。P4 清理前全量验收为 26/26（功能 22 + 架构 4）。验收次数、清理结果和阶段状态见 [实施计划](../docs/backend-implementation-plan.md)；存储见 [database.md](../docs/database.md)。

## P5 编排与工作台

已实现任务编排领域、持久 effects/outbox、独立 Worker 会话和工作目录、建议评估与 Mandate 授权、每 2 秒调度、启动 Watchdog 与人工待办。默认仅观察事件；接单和继续由本地用户明确发起。详见 [编排 API、配置与恢复说明](../docs/orchestration.md) 和 [HTTP 契约](../contracts/http/openapi.yaml)。

MCP 支持可配置的 2025-11-25 Streamable HTTP 连接与工具发现，需在本地配置登记后从工作台显式连接；语音、媒体、翻译等仍按需配置，不能把 unavailable 当作可用适配器。


## 本地备份与断点恢复

等当前任务和工具执行收尾后，通过已认证的本地会话调用
`POST /api/v1/system/backup`。归档保存在数据目录的 `backups` 下，
包含 SQLite 快照、记忆、技能、工作区文件及快照索引中的工具输出。
如果已登记的输出文件缺失或损坏，导出会报错。

把待恢复的 `.rkpack` 放进当前数据目录的 `backups`，向
`POST /api/v1/system/restore` 提交 `{"filename":"归档名.rkpack"}`。
接口校验归档并返回独立的 `dataDirectory`。验证恢复时，以
`--rikkahub.storage.data-directory=<dataDirectory>` 启动应用。
可以加 `--rikkahub.orchestration.scheduler-enabled=false`，暂停等待中任务的自动调度。
应用启动时会收尾中断的会话、审批和工具调用。主机管理的会话工作目录会映射到恢复目录，
旧会话自定义的工作目录仍使用原路径。
