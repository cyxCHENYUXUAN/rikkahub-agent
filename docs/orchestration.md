# 任务编排与人工工作台

P5 在聊天内核之上提供可选任务编排。启动默认为 observe：手动、定时或本地 Webhook 事件仅创建 Trigger、Thread、Eval，不创建 Worker，不调用模型。用户在工作台点击“接单并执行”后，才冻结助手配置并签发一个有效期 24 小时的 Mandate。

## 执行与授权

`agent-core/orchestration/Engine` 是领域写入口，包含 Intake、Evals、Tickets、Assignments、Replies 五部分。backend 的 `JdbcEngineStore` 在一个短事务中保存发生变化的独立聚合记录，并追加 effects/outbox；模型、Shell、审批等待均在事务之外执行。

每个 Ticket 使用 `worker-<ticketId>` 会话和 `data/workspace/tickets/<ticketId>` 目录。Worker 使用与聊天相同的流式循环、工具审批与输出存储，但不共享消息历史。Mandate 保存固定版本的模型、权限、提示词配置；恢复或继续不会读取新助手配置来隐式扩大权限。

`worker_report` 是无外部副作用的汇报工具，支持 PROGRESS、WAITING、READY、BLOCKED、FAILED、DROPPED。报告与工具结果一起持久化，执行器实际排空后才结算 Assignment、释放槽位。没有显式报告时，成功回复成为 READY，等待用户验收；失败成为待处理任务。报告工具不能授予权限。

人的补充消息在同一个 Ticket、会话和 Mandate 下创建新 Assignment，不触发新 Eval。`Idempotency-Key` 在 Assignment 内持久化；相同 key/正文重放已有 Assignment，不再调用模型；不同正文返回 409。停止任务会撤回 Mandate、取消运行中的 turn；已运行的 Shell 需要实际 drain，不能当作没有执行过。

## 建议评估

Eval 分两层：可选模型建议与 host 接单决定。默认不配置评估模型，建议显示 UNAVAILABLE，仍可由用户直接决定。显式 `POST /orchestration/evals/{id}/evaluate` 可选择已有助手进行一次建议评估；也可配置 `rikkahub.orchestration.eval-assistant-id` 为新事件自动生成建议。**该配置会产生模型费用**，但无论建议为何，都不会自动接单。

建议模型没有工具；只能返回 decision/reason。调用前持久标记已尝试，调用后的 usage 归入 `purpose=eval`。进程在评估期间中断时，启动不会自动重试；用户可明确再次请求评估。撤回或忽略 Eval 后，迟到的建议不能改变 host 决定。

## 调度、恢复与副作用

`PassScheduler` 每 2 秒先收敛已运行 Worker，再处理持久 effects/outbox，最后处理新评估和定时来源。`rikkahub.orchestration.worker-slots` 默认 2，范围 1–32；人工输入可额外使用一个槽位。容量不足只推迟工作，不消耗执行重试次数。

队列使用 claim_token 和 lease_until。迟到确认不能修改新租约；仅 `worker.start` 与本地通知 `local.reply` 允许安全重试。未知外部效果不自动重放；超期进入 DEAD 待办。outbox 当前仅交付本地通知，不向邮件、聊天平台或第三方 Webhook 发送消息。

人工检查完成后，可以在工作台点击“已检查，不再提示”，或调用 `POST /orchestration/work/{effects或outbox}/{id}/acknowledge`，正文 `{note}`。该操作只把 DEAD 标记为已处理，保留错误与检查备注，不执行或重试原副作用。

启动顺序：进程锁 → Flyway → 会话恢复 → Watchdog → HTTP/调度。Watchdog 按 Ticket 创建时间恢复最老任务，将中断 Assignment 标为 BLOCKED、Ticket 标为 WAITING，并给出先检查工作目录和外部状态的提示。所有已结束 Assignment 的残余输入都会取消；再次启动也重复清扫，覆盖恢复中途再次崩溃的情况。恢复不自动重发付费模型或工具；用户继续后，新 Assignment 带 recovery note。

## 来源与 API

所有接口都使用现有本地 Host/Origin/token 或浏览器 cookie 校验，不提供匿名公网 Webhook。

| 接口（前缀 `/api/v1`） | 作用 |
| --- | --- |
| `GET /orchestration` | 聚合快照，包含 Trigger/Thread/Eval/Mandate/Ticket/Assignment/Reply |
| `GET /orchestration/todos` | 待决定 Eval、待处理/待验收 Ticket、DEAD 效果与通知 |
| `POST /orchestration/triggers` | `{source, externalId, threadKey, text}`；source+externalId 去重 |
| `POST /orchestration/webhooks/{source}` | 本地认证的统一 Webhook intake；正文同上但不含 source |
| `POST /orchestration/evals/{id}/accept` | `{assistantId}`；重复接单返回同一 Ticket |
| `POST /orchestration/evals/{id}/resolve` | `{decision: IGNORE或STOP}` |
| `POST /orchestration/evals/{id}/evaluate` | `{assistantId}`；显式请求一次付费建议评估 |
| `POST /orchestration/tickets/{id}/messages` | `{text}` + Idempotency-Key；同一授权继续 |
| `POST /orchestration/tickets/{id}/stop` | 撤回授权并取消执行 |
| `POST /orchestration/tickets/{id}/close` | 已排空的任务验收完成 |
| `GET /orchestration/tickets/{id}/messages` | Worker 的当前消息 surface |
| `GET /orchestration/tickets/{id}/replies` | 持久本地回复 |
| `GET/POST /orchestration/sources` | 列表/创建 interval Source；创建正文 `{threadKey,text,intervalSeconds}` |
| `PATCH /orchestration/sources/{id}` | `{enabled}`；启停定时来源 |

定时 Source 最小 60 秒，最大一年；每个 tick 以预定 UTC 时间作为 externalId。intake 后再推进下一时刻，崩溃重入按相同 tick 去重。错过的历史 tick 不逐个补发，避免恢复时事件洪峰。GitHub/Feishu 可由本机桥接器转成统一 intake；当前没有专用公网签名适配器或对外回复能力。

## MCP 按需接入

本轮补充 `HttpMcpClient`，支持 MCP 2025-11-25 Streamable HTTP，并协商兼容 2025-03-26 / 2025-06-18。支持初始化、工具分页、JSON/SSE 响应、会话 header、超时/取消通知、响应大小限制、关闭连接；不声称支持其他协议版本、stdio、sampling 或 elicitation。协议依据：[传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)。

在 `config/application.local.properties` 中配置：

```properties
rikkahub.mcp.servers[0].id=my-tools
rikkahub.mcp.servers[0].url=http://127.0.0.1:9000/mcp
# 可选，只存引用：
rikkahub.mcp.servers[0].credential-ref=env:MY_MCP_TOKEN
```

启动不会建立远程连接。在工作台“连接与模型”中连接，或调用 `POST /api/v1/mcp/servers/my-tools/connect`；`DELETE /api/v1/mcp/servers/my-tools/connection` 断开。工具使用 `mcp__<server>__<tool>` 名称，经过现有权限/审批管线；远端返回的工具 schema、内容或错误不能覆盖本地系统提示词和 Mandate。能力接口只在实际连接成功后报告 MCP 可用。

## 工作台运行

项目根目录分别运行 `npm run dev:backend` 与 `npm run dev:frontend`，打开 `http://localhost:5173`。开发启动器默认仅为开发进程启用允许该 origin 的 CORS，后端独立启动仍默认关闭；自定义环境值可以覆盖。前端代理 `/api` 到后端，保留 Origin 校验，不改写成可信来源。更换后端端口时，两侧使用相同 SERVER_PORT。

工作台提供对话与 SSE、取消/归档/手动压缩、单次工具审批、任务与人工待办、Markdown 记忆编辑/检索/删除、模型用量、provider/assistant 配置及 MCP 连接状态。价格未配置时不把零成本当作真实账单；语音、媒体、翻译按架构按需接入，未配置时明确不可用。
