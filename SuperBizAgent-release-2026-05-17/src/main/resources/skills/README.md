# Agent Skills 技能目录

本目录存放 spring-ai-alibaba 1.1.2.0 Agent Skills（`skills.enabled=true` 时生效）。

## 目录结构

每个技能一个子目录，**必须包含 `SKILL.md`**：

```
skills/
└── oncall-runbook/          # 技能名（建议小写字母、数字、连字符）
    ├── SKILL.md             # 必需：YAML frontmatter（name + description）+ 正文
    ├── references/          # 可选：补充文档，模型按需读取
    ├── examples/            # 可选
    └── scripts/             # 可选：脚本（需配合 PythonTool/ShellTool 使用）
```

## SKILL.md 格式

```markdown
---
name: oncall-runbook
description: 一句话说明何时使用本技能（会被注入系统提示，供模型判断是否调用）
---

# 技能名称

正文：功能说明、使用方法、可用资源列表等。模型调用 read_skill(skill_name) 后加载完整内容。
```

## 生效方式

- 开关：`skills.enabled`（application.yml），默认 `false`（纯预留）。
- 来源：`skills.registry=classpath`（默认，本目录随 jar 打包）或 `filesystem`（项目目录/用户目录，可热更新）。
- 启用后，Agent 的系统提示会注入技能清单（追加式，不影响现有 system-prompt.md），
  模型按需调用 `read_skill` 加载完整 SKILL.md（渐进式披露，节省 token）。
