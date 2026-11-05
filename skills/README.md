# 内置技能

`builtin/` 保存随项目发布的技能源文件，每个实际技能包含自己的 `SKILL.md` 和支持文件；`manifest.json` 记录清单。

当前运行期的内置技能由 backend classpath 的 `skills/builtin/*/SKILL.md` 加载（例如 `local-workspace`），用户技能放在 `data/skills/<name>/SKILL.md`。`SkillCatalog` 会校验 frontmatter 并跳过格式错误的技能；系统提示词只暴露 name + description，正文通过 `skill` 工具按需加载。
