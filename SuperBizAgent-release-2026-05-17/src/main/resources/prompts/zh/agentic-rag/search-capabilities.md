---
version: 1
modified: 2026-08-01
author: chief
changes: "从 GetSearchCapabilitiesTool.getSearchCapabilities() 提取"
model: lightweight
---

{
  "knowledgeBase": "内部运维文档（CPU高负载/内存高负载/磁盘高负载/服务不可用/响应延迟等）以及用户上传的文档",
  "defaultTopK": {{defaultTopK}},
  "maxTopK": 20,
  "searchModes": ["hybrid_dense_bm25_{{hybridStatus}}"],
  "capabilities": ["keyword_search", "semantic_search", "rerank_{{rerankStatus}}"],
  "queryRewriteStrategies": ["prompt_rewrite", "hypothetical_answer", "detail_abstract", "direct"],
  "activeRewriteStrategy": "{{rewriteStrategy}}",
  "agenticLimits": {
    "maxSearchRounds": {{maxSearchRounds}},
    "minRelevanceScore": {{minRelevanceScore}}
  },
  "tools": {
    "searchKnowledgeBase": "执行检索，支持指定 topK",
    "evaluateSearchResults": "评估检索结果相关性",
    "refineQuery": "根据反馈改写查询",
    "decomposeQuestion": "拆解复杂问题为子问题",
    "queryInternalDocs": "简化版检索（一次调用，不拆解）"
  }
}
