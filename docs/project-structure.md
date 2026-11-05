# 项目结构与边界

| 目录 | 职责 |
| --- | --- |
| frontend | React 页面、组件、状态、主题、多语言及接口客户端 |
| backend | 唯一 Spring Boot 入口，产品业务、通信、数据访问及应用装配 |
| agent-core | 无 Spring 依赖的 Agent 运行核心，普通 Java 库 |
| plugins | 七类能力适配器，普通 Java 库，只依赖 agent-core |
| contracts | HTTP、SSE、WebSocket、错误及插件配置规范和类型生成输入 |
| config | 默认配置、开发示例、Agent 配置及插件组合，不保存真实密钥 |
| prompts | 系统、标题、翻译、压缩、记忆整理提示词 |
| skills | 项目随附技能及版本清单 |
| tests | 端到端、协议兼容、视觉验收及隔离数据 |
| scripts | 开发启动、构建、交付和迁移工具 |
| docs | 架构、开发约定、协议说明及运行手册 |
| references | 上游来源、版本记录、架构笔记和许可证 |
| data | 数据库、附件、事件、记忆、技能、工作区、日志、备份及私有配置，忽略版本控制 |
| dist | 最终发布结果，忽略版本控制，由交付流程生成 |

## 目录命名与同名目录分工

本地运行数据统一放在根目录 data，标准路径为 database、files、memory、skills、workspace、tool_outputs、backups，另保留 config 和 logs。数据库唯一默认路径为 data/database/rikkahub.db，由 StoragePaths 统一解析和创建；启动位置不改变该路径。

下面的相似目录分别承担不同职责，不合并：

| 目录组合 | 分工 |
| --- | --- |
| config 与 data/config | 可提交的配置模板与本地私有运行配置 |
| skills 与 data/skills | 随项目发布的内置技能与用户安装、编辑的技能 |
| plugins 与 contracts/plugins | Java 插件实现与插件通信、配置规范 |
| frontend/dist 与根 dist | Vite 前端构建结果与最终发行包组装目录 |
| features/workspace 与 data/workspace | 工作区管理业务代码、持久工具输出索引与每个会话的工作区实际文件 |
| features/approvals 与 data 库 approvals 表 | 审批投影、持久 broker 与审批 HTTP；决定只记录意图，状态由会话事件结算 |
| 各模块 src/main | 各自独立的源码入口，由 Maven 模块边界区分 |

SQLite 的 .db、.db-wal 和 .db-shm 是同一数据库的运行文件，不是多份数据库，运行时不能将后两者当作重复文件删除。

## Java 模块

根 pom 管理 Java 17、Spring Boot 4.1.2 和模块版本。Jackson 3 的 JSON 类型位于 `tools.jackson`，注解仍位于 `com.fasterxml.jackson.annotation`；版本由 Boot BOM 管理。plugins/pom 聚合插件并共享测试依赖与边界检查。backend 依赖 agent-core 和七个插件，是唯一配置 Spring Boot 打包插件的模块。

插件：model-providers、search-providers、mcp-client、workspace-runtimes、speech-providers、media-providers、builtin-tools。

依赖方向：backend → agent-core；backend → plugins；plugins → agent-core。agent-core 不依赖 Spring、HTTP、SQL 实现或产品业务；插件不得反向依赖 backend。

## 功能包

backend/features 已实现 chat、assistants、providers、approvals、workspace，并预留 extensions、search、memory、skills、mcp、files、translation、speech、media、favorites、usage、backup、preferences、setup。

chat 已实现 api、application、infrastructure/persistence 与 agent 主链路；provider/assistant 的配置从各自 application 边界访问，setup 提供本地 cookie bootstrap。其他功能包仍按阶段预留；package-info.java 仅说明边界，不表示对应业务已实现。

agent-core 预留 api、loop、session、message、context、model、tool、approval、execution、event、scope、plugin、port。

## 后续实现约束

会话产品信息由 chat 管理，执行状态和模型历史由 Agent 会话事件及投影管理，有效配置使用快照。同一会话操作串行协调，保留消息分支；输入保存与模型执行分开。

取消贯穿模型流、审批和工具；压缩保留原文并检查版本；恢复不得自动重复付费或 Shell 等副作用。用量独立记录，备份恢复统一协调。文件、Shell 和终端使用相同工作区执行环境。

主数据库已选用 SQLite，通过 Spring JDBC / JdbcClient 和 Flyway 管理；附件、技能、记忆文档和工作区实际文件保留在文件系统。数据库及业务状态边界见 [database.md](database.md)。具体已实现 HTTP/SSE 协议在 contracts 中，上游固定版本核验继续见 references；不引入 ORM 或平台 SDK。
