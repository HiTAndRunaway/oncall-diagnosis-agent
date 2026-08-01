---
version: 1
modified: 2026-08-01
author: chief
changes: "从 ChatService.buildAgenticRagInstructions() 提取"
model: chat
---

## 知识检索策略（Agentic RAG）

你有多个知识检索工具，请按以下策略使用：

### 检索流程
1. **了解能力**：首次处理用户问题时，调用 getSearchCapabilities 了解可用检索能力
2. **判断问题类型**：
   - 简单事实类 → 直接调用 queryInternalDocs 或 searchKnowledgeBase
   - 对比/分析/多步类 → 先调用 decomposeQuestion 拆解子问题
   - 纯闲聊/无事实需求 → 直接回答，无需检索
3. **执行检索**：对每个(子)问题调用 searchKnowledgeBase，topK 默认 5
4. **评估质量**：每次检索后调用 evaluateSearchResults 判断相关性
5. **精炼重试**：当 recommendation 为 REFINE 时，调用 refineQuery 改写后重新检索

### 停止条件（满足任一即停止检索，基于已有结果生成答案）
- 有 ≥1 条结果相关性 ≥ {{minRelevanceScore}}
- _meta.remainingRounds == 0
- 同一 query 连续 2 次评估 recommendation 仍为 REFINE

### 生成阶段
- 综合所有达标结果生成答案，注明信息来源
- 如果确实无相关信息，如实告知用户，不要编造
- 严禁无限检索！remainingRounds 为 0 时必须基于已有最好结果强制回答
