# 开发环境

根目录运行 `npm run dev:backend`，或在 backend 目录运行 `.\dev.ps1`。

启动器使用 Node.js 标准库，无额外 npm 依赖。每秒检查 Java 模块的源码、资源和 pom.xml；合并连续保存后构建、安装本地模块，再重新启动服务。新增、修改和删除文件均参与检测，插件修改也会更新后端使用的 JAR。

构建失败会显示错误，等待修正并保存后重试。服务启动失败不会无限重试。Ctrl+C 只停止启动器创建的进程树，不按端口批量终止其他服务。

DevTools 同时支持 IDE 编译触发的重启。普通 `mvn spring-boot:run` 不会主动编译保存的源码；需要 IDE 自动构建，或使用开发启动器。

默认端口 8081，可通过 SERVER_PORT 修改。私有配置可从 config/examples/application.local.properties 复制到 config/application.local.properties；从根目录或 backend 启动均可读取，私有文件已忽略。

data 存放本地运行数据，根 dist 存放发行组装结果。SQLite 会在首次启动时自动初始化，不需要数据库服务。默认路径为 data/database/rikkahub.db，环境变量 RIKKAHUB_DATA_DIR 可更换数据根目录，详见 [database.md](database.md)。前端沿用现有 React / TypeScript / Vite 工程。

Java 验收运行 `mvn verify`，同时执行模块依赖约束和现有 ArchUnit 架构门禁。开发期快速构建可用 `mvn verify -DskipTests`；前端验证为 `npm run build:frontend`。自动重启可临时修改 application.properties 中的应用名称，观察重新构建和 Started Main 日志后恢复原值，无需新增测试接口。

P1 内核、P2 聊天主链路、P3 工具/审批/工作区与 P4 上下文/技能/记忆/用量已接入。`RuntimeServices` 注入模型、工具、压缩与宿主端口，`DefaultAgentRuntime` 管理 Agent；内存版本 session/inbox 必须是同一个 `InMemoryAgentStore`。产品环境使用 SQLite/JDBC、配置 provider 路由、HTTP/SSE、本地认证、启动恢复、每个会话独立的本地执行环境、提示词装配与压缩、技能渐进披露、Markdown+FTS5 记忆和 usage 聚合；不要把内存运行时当成持久化后端，也不要把 shell/terminal 的宿主账号执行权限当成 OS 沙箱。搜索由 `rikkahub.search.searxng-url` 配置；未配置的搜索/MCP/翻译/语音/媒体在 capabilities 中明确为 unavailable。

后端当前为 Spring Boot 4.1.2 / Jackson 3 / JUnit Jupiter 6，Java 字节码仍以 17 为基线。接口与认证联调示例见 [backend/README.md](../backend/README.md)，REST/SSE/错误以 contracts 为准。`npm run dev:backend` 为开发进程默认启用 `rikkahub.security.development-cors=true`，只允许 `http://localhost:5173`；独立 Java 启动仍默认关闭。前端统一从 localhost:5173 打开，Vite 将 `/api` 代理到后端，保留 Origin 校验和 HttpOnly cookie；不要混用 127.0.0.1 的前端地址。SERVER_PORT 改动需同时传给两个开发进程。

日常验收使用本地协议服务器与隔离临时 SQLite，不连接项目用户库。P2 另于 2026-11-04 按用户明确授权使用 `DASHSCOPE_API_KEY`/DashScope `qwen-plus` 完成一次最小真实外部模型 HTTP→SSE 验收，仍使用隔离临时数据且密钥未写入日志。P1/P2/P3/P4 的临时验收文件已按用户要求在各阶段最终通过后删除；当前 `mvn clean verify` 只保留 P0 架构门禁（4 项），不再执行已删除的功能场景。具体测试数、清理结果与各阶段验收记录见 [实施计划](backend-implementation-plan.md)。
