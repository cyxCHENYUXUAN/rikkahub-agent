# 能力插件

每个子模块是普通 JAR，只依赖 agent-core。backend/bootstrap 负责装配；插件不能反向依赖 backend。当前只初始化构建与包边界，尚未接入平台 SDK。
