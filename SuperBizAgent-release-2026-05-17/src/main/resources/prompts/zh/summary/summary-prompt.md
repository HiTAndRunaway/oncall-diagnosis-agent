---
version: 1
modified: 2026-08-01
author: chief
changes: "从 SummaryGenerator.buildSummaryPrompt() 提取"
model: lightweight
---

请将以下对话历史压缩为一段不超过{{maxLen}}字的摘要。
摘要应包含：关键主题、重要信息、用户需求和已得到的结论。
只输出摘要文本，不要包含任何前缀或说明。

--- 对话历史 ---
{{historyText}}
--- 对话历史结束 ---

请输出摘要：
