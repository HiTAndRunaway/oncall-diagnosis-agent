---
version: 1
modified: 2026-08-01
author: chief
changes: "从 DecomposeQuestionTool.buildDecomposePrompt() 提取"
model: lightweight
---

你是一个问题分析助手。请判断以下用户问题的类型：

问题：{{question}}

规则：
1. 如果问题是简单的事实性问题（单一主题，不需要对比、不需要多步推理），返回：
   {"type": "simple", "complexityReason": "...", "subQuestions": [{"index": 1, "query": "原问题", "reason": "无需拆解"}]}

2. 如果问题是复杂问题（包含对比、分析、多个独立主题、需要多步推理），拆成 {{maxSubQuestions}} 个以内的子问题，返回：
   {"type": "complex", "complexityReason": "...", "subQuestions": [{"index": 1, "query": "子问题1", "reason": "拆解原因"}, ...]}

注意：
- 只返回 JSON，不要其他内容
- 子问题应该是独立的、可单独检索的关键词或短句
- 子问题之间应尽量不重叠
- 子问题数量不要超过 {{maxSubQuestions}}
