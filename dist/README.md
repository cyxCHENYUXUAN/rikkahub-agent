# RikkaHub Agent 本地单机发行版

本目录为 RikkaHub Agent 的开箱即用本地独立交付包。无需安装 Node.js、Vite 或配置复杂开发环境，只要系统具备 Java 17+ 运行时即可一键启动。

---

## 快速启动

### Windows 用户
双击运行：
```cmd
dist\bin\start-rikkahub.bat
```
启动器将自动完成：
1. 校验本地 JRE/JDK 17+ 运行环境；
2. 加载内嵌前端的独立后端 JAR；
3. 初始化用户专属独立数据目录；
4. 自动在系统默认浏览器中打开工作台 `http://127.0.0.1:8081`。

### Linux / macOS 用户
在终端中执行：
```bash
chmod +x dist/bin/start-rikkahub.sh
./dist/bin/start-rikkahub.sh
```

---

## 独立数据目录与存储机制

为彻底解绑代码仓库与用户私有数据，RikkaHub Agent 默认将全部数据存储在操作系统标准用户数据目录中：

| 操作系统 | 默认存储路径 |
| :--- | :--- |
| **Windows** | `%LOCALAPPDATA%\RikkaHub`（例如 `C:\Users\<用户名>\AppData\Local\RikkaHub`） |
| **macOS / Linux** | `~/.rikkahub` |

### 数据目录结构
```text
<数据目录>/
├── database/rikkahub.db      # SQLite 核心业务库 (Flyway V1–V10 事件溯源)
├── config/                   # 本地私有配置文件与模型配置
├── memory/                   # 用户 Markdown 长期记忆物理文件
├── skills/                   # 本地导入的技能包
├── workspace/                # 独立任务沙箱与 Ticket 隔离工作目录
├── tool_outputs/             # 大工具输出溢出转储 (out_*)
└── backups/                  # 数据库与全景归档热备份目录
```

### 自定义数据目录
可通过环境变量或命令行参数指定数据目录：
- 环境变量：`set RIKKAHUB_DATA_DIR=D:\MyAgentData`
- 命令行参数：`--rikkahub.storage.data-directory=D:\MyAgentData`

---

## 自定义端口
如需修改监听端口（默认 `8081`），可设置环境变量：
```cmd
set SERVER_PORT=9090
dist\bin\start-rikkahub.bat
```

---

## 版本升级与备份恢复
1. **就地升级**：直接替换 `dist/lib/backend.jar` 或更新最新仓库编译的 JAR，用户数据独立保存在系统数据目录下，重启后 Flyway 自动安全执行增量数据库迁移。
2. **完整数据备份**：直接复制或压缩备份上述“数据目录”即可完整迁移或还原全部会话历史、任务工单与长期记忆。
