# 项目提示词

system、title、translation、compression、memory 分别维护系统、标题生成、翻译、上下文压缩和记忆整理模板。

当前运行期默认模板打包在 `backend/src/main/resources/prompts/`（system/compression/memory），以保证可执行 JAR 自包含；本目录保留发行/编辑源文件。模板只允许白名单占位符，不执行 SpEL 或脚本。
