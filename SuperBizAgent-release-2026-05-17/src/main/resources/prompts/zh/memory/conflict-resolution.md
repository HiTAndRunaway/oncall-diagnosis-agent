---
version: 1
modified: 2026-08-01
author: chief
changes: "从 MemoryExtractor.resolveConflict() 提取"
model: lightweight
---

用户已有以下记忆：
旧记忆: "{{oldContent}}" (置信度: {{oldConf}})

从最新对话中提取到：
新记忆: "{{newContent}}" (置信度: {{newConf}})

判断新旧记忆的关系：
- UPDATE: 新信息是旧信息的更新（如版本升级），覆盖旧记忆
- MERGE: 两者可以合并为一条更完整的记忆
- NEW: 两者是不同的信息，应该各自保留

输出 JSON: {"action": "UPDATE|MERGE|NEW", "reason": "..."}
