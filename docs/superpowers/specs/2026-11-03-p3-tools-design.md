# P3 工具、审批与工作区设计

## 目标与依据

面向继续开发本项目的后端工程师。依据 backend-architecture.md §4.5、§8.2、§8.5、§11、§13，实现真实本地工具和产品审批闭环；后续顺序为 P4、P5。用户要求各阶段验收后删除该阶段新增临时测试，保留原有架构门禁。

P2 生产代码及本地协议验收已完成；真实外部模型退出项仍未验收。继续 P3 是对用户反复要求依次开发的执行解释，不代表外部模型已通过或用户明确豁免该项。无可用模型配置时，使用隔离数据库和本地协议 fixture，不读取用户库、真实密钥或调用付费模型。

## 技术与边界

- Java 17，工作区已有 Spring Boot 4.1.2 / Jackson 3 / Jupiter 6；内核和插件不依赖 Spring。
- 非 Git 工作区，不创建仓库、提交、worktree、推送。
- 文件工具依赖 core FileSystem，执行适配器统一验证真实根及现存父目录；拒绝链接、junction、路径逃逸、Windows ADS/设备路径。文件访问是应用层边界；Shell/terminal 不是 OS 沙箱，获得审批意味着任意代码执行权限，必须在 UI/契约说明。
- 架构 §13 权威允许 v1 pipe terminal；mode 明确为 PIPE，输出有界可读，resize 明确 unsupported。真实 PTY 可选，不纳入 v1 必需退出，绝不以 Process 管道冒充 PTY。
- argv 分离，不隐式拼接 shell 字符串。仅 shell 工具显式命令解释；清除继承凭据环境，只保留启动必需的固定白名单。timeout/cancel 清理实际进程树、排空双流，不能将 Future 完成等同实际退出。
- 每 session 独立环境和工具注册；同一环境中的 files/process/terminal/jobs 共用一个 root。资源随 writer 退休/停机关闭。

## 工具与输出

read/write/edit/glob/grep/shell/terminal 七类工具，参数严格校验。read/glob/grep 可并发；write/edit/shell/terminal 独占。edit 需精确且唯一匹配，不静默改多个位置。遍历、读取、输出、后台任务和终端数量均有界。工具仅使用 core 端口，不直接依赖其他 plugin 或 backend。

进程输出在采集阶段有界；超限数据落 data/tool_outputs，摘要中标明截断和安全引用。最终工具内容同样经过输出限额，spill 引用进入持久 ToolResultEvent，而不只存丢失的 metadata。引用绑定 session，读取接口校验归属，不接受任意磁盘路径。输出存储失败返回错误且不重跑已执行工具。

## 权限与审批

安全默认 WORKSPACE_WRITE：文件读取/工作区写入允许，shell/terminal/后台等任意执行 ASK。READ_ONLY 拒绝写入和任意执行；FULL_ACCESS 仍受显式有序规则限制。策略解析 session override > assistant > global，在 turn 开始冻结。NEVER 拒绝需要 ASK 的调用，不解释成免审批。规则无效失败关闭。

审批绑定 id/session/turn/call/tool/原始参数摘要/过期时间，仅 allowed-once/rejected/cancelled/unavailable 四态。审批生命周期必须在会话写者线程执行：提交 tool/call → asked 与 PENDING 投影同事务 → broker 等待（无连接、无工具池占用）→ writer 提交 decided → 才能执行副作用。HTTP 只 CAS 决定并唤醒；不直接 append 会话事件。重复相同决定幂等，冲突或迟到决定拒绝。取消、超时不放行；重启即 unavailable，即使 HTTP 曾 CAS 但 decided 尚未写入。恢复不重放 Shell。

当前循环是同步阻塞设计；审批等待放到独立有界 turn/wait 执行资源，不得占满工具池或阻塞 HTTP 命令队列。若需改变 loop continuation，必须保留原 per-session 单写者和真实 drain 语义。

## 并发与持久性

同 step 连续 concurrencySafe 工具构成并行组；未知/抛异常默认独占，前后形成顺序屏障。会话事件由唯一写者按调用顺序提交，工具 worker 不触碰 expectedSeq。审批生命周期不能在并行 worker 中写事件：先由写者完成各调用的 preflight/approval，再执行已授权组。每个调用恰好一个结果；取消后未开始项产生取消结果，已开始项真正 drain 后才能进入下一独占项或关闭 turn。

使用新迁移 V7 起，不改 V1–V6。审批查询投影由 SessionCommitService 驱动。旧事件/配置构造器保持兼容，新增字段有明确缺省值。真实模型、真实 PTY、OS 沙箱不因本地验收而标为完成。

## 验收与清理

TDD：每项新行为先观察失败，再实现通过。临时目录、临时 SQLite、本地模型 fixture；验证文件路径/链接、精确 edit、argv 注入边界、环境清理、进程树、终端/后台归属、输出 spill、审批全态/CAS/取消/重启、并发屏障、P2 文本/幂等/SSE/恢复不退化。最终独立 review，完整 mvn clean verify。阶段验收后删除本阶段临时测试及其 runner/临时数据，再 clean verify 原4架构检查，文档记录删除后不再复跑功能案例。
