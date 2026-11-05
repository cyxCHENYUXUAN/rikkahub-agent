# RikkaHub Agent

React + TypeScript 前端，Java 17 / Spring Boot 4.1.2 多模块后端；JSON 使用 Jackson 3（`tools.jackson`），注解仍使用 `com.fasterxml.jackson.annotation`。

## 开发

需要 Node.js 22.12+、Java 17+ 和 Maven 3.9+。在项目根目录执行：

```powershell
# 首次安装前端依赖；已有 node_modules 时可省略
npm --prefix frontend ci

# 终端一：后端，监听文件并自动构建、重启
npm run dev:backend

# 终端二：React，使用 Vite 热更新
npm run dev:frontend
```

后端默认端口 8081，更换端口可在启动前设置 `$env:SERVER_PORT = '8082'`。已有服务需先停止才能使用相同端口。

## 构建

```powershell
mvn package -DskipTests
npm run build:frontend
```

Java 产物在各模块 target，前端产物在 frontend/dist。根 dist 预留最终发行组装，当前尚未实现发行打包。

目录约束见 [docs/project-structure.md](docs/project-structure.md)，开发操作见 [docs/development.md](docs/development.md)。SQLite 存储和版本化迁移已接入，详见 [docs/database.md](docs/database.md)。

完整后端骨架与设计理由见 [docs/backend-architecture.md](docs/backend-architecture.md)；后端实施顺序、验收标准与当前进度见 [docs/backend-implementation-plan.md](docs/backend-implementation-plan.md)；`agent-core` 的 P1 公共 API 固定在 [docs/kernel-api-contract.md](docs/kernel-api-contract.md)。构建门禁（enforcer + ArchUnit）已生效：`mvn verify` 会检查模块依赖方向与 feature 边界。

P2 聊天、模型流、SSE、持久化与本地认证已完成，既有真实外部模型验收记录保留。P3 提供内置工具、独立工作区、单次审批与持久输出，本轮补齐 Windows 显式命令执行回归。P4 提供上下文压缩、技能渐进披露、Markdown+FTS5 记忆、中文片段检索、用量与按需搜索/MCP HTTP 连接。

P5 已实现默认 observe 的任务编排、固定 Mandate、独立 Worker、effects/outbox、调度与恢复，以及人工待办工作台。用户接单后才启动 Worker；人的补充消息沿用同一授权，不触发新评估；崩溃后不自动重放模型和工具。React 工作台还接通了对话/SSE、审批、记忆、用量与模型配置。操作与可选外部集成边界见 [任务编排与工作台](docs/orchestration.md)，配置模型和认证见 [backend/README.md](backend/README.md)。阶段验收、测试清理与构建证据见 [实施计划](docs/backend-implementation-plan.md)。
