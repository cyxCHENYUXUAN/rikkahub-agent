# 本地存储

使用 Xerial SQLite JDBC 3.54.1.0、Spring JDBC / JdbcClient、Spring 事务和 Flyway。只有 backend 持有数据库连接，agent-core 继续通过存储接口使用外部能力。

## 路径

默认数据目录固定为项目根目录的 data，从根目录或 backend 启动都使用同一文件。脱离源码仓库分发时，默认在可执行 JAR 所在目录下创建 data。可用 RIKKAHUB_DATA_DIR 或 rikkahub.storage.data-directory 指定其他路径；相对路径基于项目根目录或分发目录解析，不依赖终端当前目录。

```text
data/
├── database/rikkahub.db
├── files/
├── memory/
├── skills/
├── workspace/
├── tool_outputs/
└── backups/
```

首次启动自动创建目录和数据库，所有内容已排除版本控制。应用配置及日志仍可使用 data/config 和 data/logs。

## 连接和事务

每个连接启用 WAL、foreign_keys=ON、synchronous=FULL，busy_timeout 默认 10000 毫秒，可由 rikkahub.storage.busy-timeout-ms 修改（1–60000）。

当前单实例使用一个连接，让包括写入在内的数据库操作排队，连接等待时间为 busy_timeout 加 5 秒。这是本地应用的保守初始配置，应用内部读取也会串行；WAL 仍允许外部读取和写入并行。模型请求、流式推送、审批等待、Shell 和媒体调用都不能持有数据库连接或事务。

业务代码注入 JdbcClient 执行带参数 SQL，使用 @Transactional 或 TransactionTemplate 提交短事务。不同 Agent 的模型执行可以并行，数据库提交短暂排队。不要在流式 token 回调中逐字写库，应内存合并后分批提交。

## 迁移与表范围

SQL 位于 backend/src/main/resources/db/migration，按 V1__description.sql、V2__description.sql 递增，已执行文件不能修改。Flyway 用 schema_migrations 记录版本和校验和，禁用 clean，不自动为未知旧库建立基线。

已实现的迁移如下；没有插入样例记录、默认 provider 或真实凭据。

| 版本 | 表 |
| --- | --- |
| V1 | `app_settings`；保持原文件不变 |
| V2 | `providers`、`assistants`、`idempotency_keys`；provider 只保存 credential_ref |
| V3 | `conversations`、`message_nodes`、`messages`、`inbox_items` |
| V4 | `agent_sessions`、`session_events` |
| V5 | `usage_records` |
| V6 | 从权威 session_events 修复旧消息投影中缺失的 content/tool-call type 判别字段；不改写事件原文 |
| V7 | `approvals`（会话绑定的持久审批投影：asked/decided 序号、参数摘要、requested_decision CAS 意图）与 `tool_outputs`（会话绑定输出索引：配额收取、内容哈希、READY/PENDING 状态） |
| V8 | `conversations` 的会话级工具/审批策略覆盖列（`tool_permission_mode`、`approval_policy`、`tool_rules_json`）；NULL 表示继承 assistant |
| V9 | `memory_documents`（Markdown 记忆索引副本）与 `memory_fts`（FTS5 全文索引）；正文以 `data/memory/*.md` 为准 |
| V10 | `orchestration_entities`（7 类独立领域记录）、`effects`、`outbox` 与 `orchestration_sources`；含到期索引与租约状态约束 |

`files`、媒体等后续表在对应功能确定时另增迁移，不能回改已执行版本。MCP 地址当前来自本地配置，不创建未经使用的服务配置表。已有数据库在下次启动时由 Flyway 向前迁移到 V10；整库快照自动包含新增表、索引与领域记录。V6 可重建有事件来源的旧投影，不为无来源的损坏行伪造事件。

事件是执行历史依据，消息表是查询视图。当前 `JdbcSessionStore.commit` 委托 `SessionCommitService`，在同一短事务里分配 seq/time，检查序号/状态 CAS，追加事件、更新消息投影和配置快照、写用量并消费 inbox。投影失败、过期 claim 或用量来源不匹配均回滚整批；不会先提交事件再补投影。事务返回后连接已归还，内核才发送 SessionCommitted；SSE 只在提交后入队，发送和回放不占用原提交事务。

`JdbcInboxStore` 在独立短事务认领/释放，包含新 token、owner、attempts 和 lease；过期/旧 token 不允许完成或释放。启动恢复与运行期 reclaim 都使用 `rikkahub.recovery.max-inbox-attempts`（默认 3）：过期租约未耗尽则重置 PENDING，耗尽则 CANCELLED，不能因启动时租约尚未过期而绕过阈值。`complete` 仅允许在本批 SessionCommit 的内部上下文中执行，有普通事务也不能绕过日志消费输入。非重试释放成为 CANCELLED。所有存储时间是 UTC 九位小数，避免文本时间精度不同导致租约误判。

提交期间只读取 session 进度小行；读取日志按 256 条分页，保持当前 Session 接口要求的连续前缀。模型、工具和通知期间不持有连接。配置快照在首次真实 request/header 前为空，随后从 configSnapshot 校验保存；普通执行不递增 metadata_version。消息表保留旧分支，以独立的可见性和位置字段维护 append/replace 结果；STREAMING 占位在完成、错误或取消时收敛。

`usage_records.purpose` 保留计费目的，模型用量关联实际 assistant/message、assistant/attempt 或压缩摘要，并核对已有 provider/model；建议评估使用独立 eval session 的完成记录，purpose=eval。`record_index` 是同事件账本记录序号，和实际模型 attempt 分开。V10 起非空 effects 与会话事件、投影、用量、输入消费同事务写入；重复效果 ID 使整批回滚，不能丢弃效果或单独提交事件。

领域设计见 [backend-architecture.md](backend-architecture.md) §7.2，实际字段/约束以版本化 SQL 为准。当前 V1～V10 已落地。审批投影与事件同事务写入，状态仅由写者的 approval/decided 事件结算；HTTP 决定只存 requested_decision，不写会话事件。启动在 datasource/Flyway 之前取得 data/.lock；会话和 Watchdog 恢复在接受请求前完成，不自动重放模型或工具。优雅关闭先停调度与建议评估，再以 Shutdown 信号排空 Agent、收敛 Assignment、关闭命令/SSE；数据库销毁在这些步骤之后。

编排存储和恢复细节见 [orchestration.md](orchestration.md)。聚合以 `(kind,id)` 独立记录，只写发生变化的 JSON；同一 Engine 命令产生的本地回复、effects/outbox 同事务。效果确认必须同时匹配 RUNNING、claim_token 和未过期 lease_until。未知非幂等效果超时进入 DEAD，由人工检查；不会因为一次租约超时自动重发外部操作。备份恢复必须同时保留数据库与 `workspace/tickets`，恢复后的旧 Mandate 仍需检查有效期与撤回状态。

记忆检索先使用 FTS5 排名；连续中日韩文本的字词片段用参数化 `instr` 补齐匹配，避免 unicode61 不分词导致无法搜索中文片段。普通 FTS 查询仍转义成字面词，不执行用户提供的 MATCH 表达式。

本轮验证使用隔离临时数据库，覆盖迁移、重启、CAS、lease、消费、投影回滚、配置与用量、fork、真实 Spring 装配和内核工具闭环，共 17 项通过；未连接项目 data 中的用户数据库。临时测试按用户要求在最终验证后删除，保留原有架构门禁。

## 文件与备份

数据库只保存配置、业务状态和文件索引，不保存附件或工作区文件正文。files、skills、memory、workspace、tool_outputs 分别保存实际文件。

DatabaseSnapshotService.createSnapshot() 在事务外执行 VACUUM INTO，生成 data/backups 下的一致性数据库快照；完成后才发布为 .db 文件，失败会清理 .partial 文件。在已有事务中调用会直接拒绝，避免占用唯一连接后再次取连接。

此服务尚未提供 HTTP 接口，也不是完整应用备份。完整归档仍需协调文件写入，再将快照与附件、技能等一同归档；运行期间不能直接复制单个 rikkahub.db。数据库只能位于本机磁盘，不支持网络共享目录或多个 Java 实例并行迁移。

参考：[SQLite WAL](https://www.sqlite.org/wal.html)、[VACUUM INTO 一致性快照](https://www.sqlite.org/lang_vacuum.html)、[Xerial 驱动](https://github.com/xerial/sqlite-jdbc)、[Flyway SQLite](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite)。
