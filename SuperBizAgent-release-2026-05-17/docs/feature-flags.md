# 特性开关

> 最后更新：2026-08-01

| 开关 | 默认值 | 依赖条件 | 说明 |
|------|--------|----------|------|
| `memory.enabled` | true | Redis 可用 | 长短期记忆系统 |
| `rag.agentic.enabled` | false | biz collection 有数据 | Agentic RAG 多轮搜索 |
| `rag.hybrid.enabled` | true | - | BM25+向量双路召回 |
| `rag.rerank.enabled` | true | - | DashScope Rerank 重排序 |
| `rag.rewrite.cache.enabled` | true | Redis 可用 | 查询改写结果缓存 |
| `cls.mock-enabled` | false | - | CLS 日志模拟 vs MCP 真实 |
| `prometheus.mock-enabled` | false | - | Prometheus 模拟 vs 真实 API |
| `superbiz.security.enabled` | false(dev)/true(prod) | - | API Key 认证 |
| `superbiz.rate-limit.enabled` | false(dev)/true(prod) | 需 security.enabled=true | 请求限流 |
| `intent.router.enabled` | true | - | 意图识别路由 |
| `session.redis.summary.enabled` | true | - | 对话摘要生成 |
| `aiops.eval.enabled` | true | - | LLM-as-Judge 质量评估 |
