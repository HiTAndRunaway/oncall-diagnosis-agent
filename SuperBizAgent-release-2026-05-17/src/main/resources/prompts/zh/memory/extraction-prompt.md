---
version: 1
modified: 2026-08-01
author: chief
changes: "从 MemoryExtractor.buildExtractionPrompt() 提取"
model: lightweight
---

分析以下对话，提取关于用户的重要信息。

已有记忆：
{{existingMemories}}

对话历史：
{{conversation}}

请提取三类信息：
1. FACT（事实结论）：用户明确提到的技术事实、环境信息、历史决策结果
2. PROFILE（用户画像）：用户的职业角色、技能领域、职责范围
3. PREFERENCE（行为偏好）：用户表达的信息呈现偏好、工作习惯、交流风格

要求：
- 只提取明确的信息，不要推测
- 每条记忆置信度 0-1，模糊信息给低分
- 如果对话中没有值得提取的信息，返回空列表
- 输出 JSON: {"memories": [{"type": "FACT", "content": "...", "confidence": 0.9}]}
