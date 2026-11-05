# 通信规范

http/openapi.yaml 是 HTTP 协议入口；sse、websocket、errors、plugins 分别保存事件、消息、错误和插件配置规范。generated 预留类型生成结果。

当前 P2–P4 接口已列入 OpenAPI：本地 cookie bootstrap、provider/assistant 配置（含 toolPermissionMode/approvalPolicy/toolRules）、对话及会话级策略覆盖、文本输入、指定 turn 取消、消息/事件查询、SSE、审批列表与单次决定（202 记录意图）、持久工具输出字节读取、手动压缩/放弃、记忆 CRUD/搜索/refresh/提取、用量聚合与 capabilities。SSE durable/ephemeral 规则见 sse/events.yaml，ProblemDetail 与错误码见 errors/codes.yaml。未实现的上传/分支等端点不列为已交付契约；当前没有引入类型生成器。shell/terminal 是宿主账号权限的任意执行，不是 OS 沙箱；terminal 为 PIPE，不支持 resize。MCP/翻译/语音/媒体在未配置时由 capabilities 明确报告 unavailable。
