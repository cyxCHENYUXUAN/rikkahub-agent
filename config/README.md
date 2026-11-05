# 配置

defaults 保存公共默认值，examples 保存开发示例，agents 保存 Agent 配置，plugin-profiles 保存插件组合。

当前只读取后端 application.properties 和可选的 config/application.local.properties；其余目录的加载器在功能实现时接入。真实密钥使用运行环境或本地私有配置。
