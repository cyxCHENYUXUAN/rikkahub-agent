# RikkaHub 工作台

React + TypeScript + Vite，本地单用户界面。

在项目根目录分别运行 `npm run dev:backend`、`npm run dev:frontend`，打开 `http://localhost:5173`。前端 `/api` 代理到 8081；修改端口时两个进程使用相同 SERVER_PORT。开发后端启动器默认允许 localhost:5173，独立后端启动仍默认关闭开发 CORS。浏览器凭据由 setup/session 写入 HttpOnly cookie，不保存在 localStorage。

功能：对话与 SSE、取消/归档/上下文压缩、工具单次审批；任务接单、独立 Worker、结果验收、同一授权继续和停止；Markdown 记忆与检索；按模型/日期汇总用量；provider/assistant 配置与 MCP 连接。

首次添加 provider 与助手后才能接单或创建对话。密钥用后端环境变量引用配置，界面只填写 `env:VARIABLE_NAME`。没有配置的外部能力会明确显示未配置。

构建：`npm --prefix frontend run build`；检查：`npm --prefix frontend run lint`。构建产物位于 frontend/dist，可用 `npm --prefix frontend run preview` 配合本地后端预览。发行时可由同一可信本地域提供静态资源和 `/api`；当前 Maven 不自动打包前端资源。

任务、授权、恢复与 MCP 的完整约定见 [编排文档](../docs/orchestration.md)。
