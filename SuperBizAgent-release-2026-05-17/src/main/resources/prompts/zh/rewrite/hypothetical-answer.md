---
version: 1
modified: 2026-08-01
author: chief
changes: "从 HypotheticalAnswerStrategy.ANSWER_PROMPT_TEMPLATE 提取"
model: rewrite
---

请对以下问题给出简要回答。不需要过于详细的解释，但需要覆盖核心要点。

要求：
- 回答控制在 200 字以内
- 涵盖问题的核心知识点
- 直接输出回答内容，不要输出任何前缀和解释

问题：{{originalQuery}}
回答：
