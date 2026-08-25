---
name: rag-flow
description: 当需要通过内部知识库（Milvus 向量检索）检索文档来回答用户问题时，使用本技能。描述一轮 RAG 的标准流程（意图判断 → 问题改写 → 向量化 → 向量召回 → 重排序），以及重排后结果不足时按反馈改写查询并重新召回的多轮策略。
---

# 一轮 RAG 检索流程（Retrieval-Augmented Generation）

本技能描述一轮 RAG 的标准执行流程，以及「重排后仍无法回答 → 根据反馈改写查询 → 重新召回」的多轮重试策略。
适用于需要从内部知识库检索文档后回答的问题（CPU/内存/磁盘告警处置、服务不可用、响应缓慢、内部流程规范、用户上传的文档等）。

## 一轮 RAG 的标准流程（7 步）

### 1. 用户输入问题
拿到用户问题原文，作为整条链路的输入。

### 2. 意图判断（Intent Routing）
先用 IntentRouter 判断问题类别，只有知识检索类问题才走本流程：
- `ALERT_DIAGNOSIS`（告警排查）→ 走 AIOps（SupervisorAgent + queryInternalDocs 单发检索）
- `KNOWLEDGE_RETRIEVAL`（知识检索）→ 走一轮 RAG 本流程
- `GENERAL_CHAT` / `UNCLEAR`（通用对话/不明确）→ 无检索需求时直接回答

### 3. 问题改写（Query Rewrite）
调用 `QueryRewriteService` 改写查询（当前策略 `prompt_rewrite`，由 LLM 改写成更清晰、更利于向量检索的表达）。
- 有 Redis 缓存（`rag:rewrite:*`），命中直接复用；
- LLM 调用失败/超时会降级为原问题，不阻断流程。

### 4. 改写后问题向量化（Embedding）
用 `VectorEmbeddingService` 将改写后的问题转成向量：
- 默认 DashScope `text-embedding-v4`；
- `litellm.enabled=true` 时走 OpenAI 兼容 `/v1/embeddings` 网关。

### 5. 向量检索召回（Recall）
`VectorSearchService.searchSimilarDocuments(rewrittenQuery, topK)`：
- **双路并行召回**：dense（向量路，L2 距离）+ BM25 稀疏（IP），RRF（Reciprocal Rank Fusion）融合；
- parent-child 分块策略会自动把命中的 child 还原为 parent 内容（small-to-big），并按 parentId 去重。

### 6. 重排序（Rerank）
召回数超过阈值（`rag.rerank.threshold`）时，用 `gte-rerank-v2`（DashScope 原生或 liteLLM `/v1/rerank`）二次精排，按相关性取 `topK` 作为最终上下文。

### 7. 生成答案
综合所有达标 chunk 生成答案，注明信息来源；确实没有相关信息时如实告知，不要编造。

## 结果不足时的多轮重试（Agentic RAG）

如果重排后的 chunk 仍不足以回答用户问题，按既有 Agentic RAG 逻辑处理：

1. 调用 `evaluateSearchResults` 评估召回结果相关性，得到 `overallRelevance` 与 `recommendation`；
2. 若 `overallRelevance < rag.agentic.min-relevance-score`（默认 0.6）→ `recommendation=REFINE`：
   调用 `refineQuery` **结合评估反馈**（哪些结果无关、缺什么信息）改写查询，然后**回到第 3 步重新走一轮召回**；
3. 满足任一停止条件即停止，基于已有最好结果强制回答：
   - ≥1 条结果相关性达标；
   - `_meta.remainingRounds == 0`（默认 maxSearchRounds=3）；
   - 同一 query 连续 2 次评估仍为 `REFINE`。

详细的多轮重试策略见 `references/agentic-rag-retry.md`。
