# P2–P5 完整收尾执行计划

依据：`docs/backend-architecture.md` §8.5–8.6、§13，`backend-implementation-plan.md` §4–7。用户已授权按文档持续实施、验收后删除本轮测试，无需再次选择实现方案。

## 约束与设计

- 继续 Java 17、多模块、SQLite、SessionCommitService 唯一会话写入入口；不改 V1–V9，不接触用户 data，不发付费调用。
- P5 内核 Engine 只依赖内核类型；Intake/Evals/Tickets/Assignments/Replies 五部分共享一个事务入口。Trigger 按 source/externalId 去重，归 Thread；Eval 的建议判定与 host Policy 分开，默认 observe。只有本机用户明确 accept 才签发固定版本 Mandate，外部内容不能授权。
- 一 Ticket 一 `worker-<ticketId>` 会话和 `workspace/tickets/<ticketId>` 工作目录。Mandate 固定执行配置、可撤回；恢复与人的 resume 使用同一 Mandate，不创建新 Eval。每轮开始重新检查授权。
- effects/outbox 在业务事务中落库，派发在事务之后。CAS 租约与 token 拒绝迟到回调；非幂等外部副作用的过期执行进入人工待办，不自动重放。Worker 启动可幂等交付到 inbox，但崩溃后的模型/工具不自动重发。
- Watchdog 按最老 Ticket 恢复中断 Assignment，加入检查工作区与外部状态的 recovery note；恢复先展示等待用户确认，用户明确 resume 才可再次付费。PassScheduler 每 2 秒处理已有存储任务，然后处理新 Eval。
- 标准 Worker 并发配置，人的消息可额外使用一个槽位；只有实际 drain 后释放槽位。待办只显示 observe Eval、等待/阻塞/失败/中断任务及 dead effect/outbox。
- 外部 Source 是可选项，本轮提供受本地认证保护的手动/Webhook intake 和持久 interval 定时 Source；GitHub/Feishu 可通过相同 webhook 接入，不添加未经授权的对外发送。
- P4 按需外部接入：补 MCP 的可配置 HTTP JSON-RPC 传输、发现、工具注册及真实能力状态；翻译/语音/媒体保留文档定义的按需扩展，不自动启用。
- 人工工作台用现有 React 工程实现：聊天、任务/待办、记忆、用量与模型配置；蓝灰工作区、紧凑导航、清楚的授权状态和空状态，不引入额外 UI 依赖。

## 实施与验收

1. [x] 复核 P2–P4 基线，建立临时回归；记录环境导致的构建限制。
2. [x] 临时 Engine 测试先失败：observe 不接单、去重、授权撤回、resume 无新 Eval、五部分事务回滚、Worker 槽位与 FIFO 恢复；实现领域与 Engine。
3. [x] 临时 SQLite 测试先失败：session+effects 同事务、租约 fencing、超期非幂等 dead、outbox CAS、迁移/重启；实现 V10 与 JDBC 存储。
4. [x] 临时 Spring/协议验收先失败：accept→持久 Worker inbox→真实运行循环→report→待办、取消撤权、恢复不重放；实现 Worker、Watchdog、Scheduler、Source 和 REST。
5. [x] MCP 协议 fixture 验证 initialize/list/call、分页、错误、取消/超时与配置门控，接入会话工具列表。
6. [x] 工作台接通真实 API；构建/lint 与浏览器流程验证，禁止用静态假数据声称完成。
7. [x] 全量 `mvn clean verify`、前端 build/lint、独立审查与必要修复；更新数据库/HTTP/阶段文档。
8. [x] 删除本轮临时测试、fixture、runner、隔离数据库与测试 class；保留原 P0 架构检查与用户数据；再运行全量干净构建。

## Review Focus

重点审查：撤权与 Worker 启动竞态；租约过期的迟到成功；崩溃后付费调用与 shell 的重放；槽位超额；重复 accept/resume 与 inbox 去重；坏 JSON 对调度的影响；MCP 远端 schema/工具结果无权覆盖本地权限；浏览器认证及失败状态。
