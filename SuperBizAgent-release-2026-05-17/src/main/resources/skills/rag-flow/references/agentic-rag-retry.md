# Agentic RAG 多轮重试策略（参考资料）

配合 `SKILL.md` 使用，详细说明「重排后仍无法回答 → 根据反馈改写查询 → 重新召回」的多轮逻辑。

## 触发条件

`evaluateSearchResults` 判定 `overallRelevance < rag.agentic.min-relevance-score`（默认 0.6），返回 `recommendation = REFINE`。

## 一轮重试动作

1. `refineQuery(原查询, 评估反馈)`：把评估反馈（哪些结果无关、缺什么信息）交给 `QueryRewriteService.rewriteWithFeedback`，改写出更有针对性的新查询；
2. 用新查询**重新走一轮标准 RAG**：问题改写 → 向量化 → 向量召回（双路 + RRF）→ 重排序。

## 停止条件（满足任一即停止）

- 有 ≥1 条结果相关性 ≥ `minRelevanceScore`；
- `_meta.remainingRounds == 0`（`rag.agentic.max-search-rounds`，默认 3，由 `AgenticRagGuard` 提供）；
- 同一 query 连续 2 次评估仍为 `REFINE`。

## 注意事项

1. 严禁无限检索；`remainingRounds` 为 0 时必须基于已有最好结果强制回答。
2. 复杂/对比/多步类问题，可先用 `decomposeQuestion` 拆解为独立子问题，逐子问题检索后再综合生成答案。
3. 每条召回结果都来自 `searchKnowledgeBase`（自动附带 `_meta` 轮次信息）或 `queryInternalDocs`（简化单发检索）。
