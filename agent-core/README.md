# Agent 核心

独立 Java 17 库，不依赖 Spring、JDBC 或产品业务。P1 内存闭环已完成：持久化事件与 surface、原子提交与 inbox lease、工具审批和 drain、冻结模型请求，以及 `RuntimeServices`、`AgentLoop`、`LoopAgent`、`DefaultAgentRuntime`。

嵌入时把同一个 `InMemoryAgentStore(Clock, IdGenerator)` 实例注入 session/inbox 两个端口；它只在当前进程内保存数据。`SessionStore.commit` 是唯一会话写入口，事件、用量、效果、输入消费与状态一起生效。工具由 `DefaultToolPipeline` 执行，宿主拥有工具 executor 的生命周期；关闭 Agent 时等待实际工具任务退出。

`AgentSpec.initialInputs` 可在创建时入队；`inject()` 以 `metadata.wake=false` 加入下一 step 的输入，不唤醒循环。模型失败重试复用冻结请求，取消和失败尝试的用量随事件提交。恢复拒绝尚有打开 turn 的会话，由后续恢复服务先处理。存储或排空服务失败会异常终结 idle/关闭 future；工具执行后结果提交失败保留打开的 turn，不重跑工具，不允许直接开始下一轮。

本轮临时内核案例验证事件、提交、租约、工具与运行时；按用户要求，验收后删除这些测试和案例，保留原 P0 架构测试，没有永久 testkit。清理后运行 `mvn clean verify` 可验证全模块构建和保留的架构测试；`mvn -pl agent-core test` 不能重新运行已删除的 P1 案例。

具体 API 见 [kernel-api-contract.md](../docs/kernel-api-contract.md)，阶段状态见 [backend-implementation-plan.md](../docs/backend-implementation-plan.md)。P2 JDBC 持久化基座已实现并通过实际内核验证；HTTP/SSE、真实模型适配器与恢复也已接入。P3 已完成（2026-11-04）：内核侧审批检查点事件、会话级策略快照、有界并行/独占工具批次、输出限额端口与 `ToolResultEvent` 持久 outputReferences，已由产品 ChatAgentService 实际装配并在真实 HTTP/可执行 JAR 链路验证；本地执行环境由 workspace-runtimes 插件提供。P4 核心已完成：`context` 的提示词装配/token 估算/压缩计划与执行描述、`skill` 摘要/正文端口、`MemoryStore`/`SearchPort`/`McpPort` 端口，以及 `ToolResultEvent` 之外的 compaction 事件兼容；编排仍属 P5。
