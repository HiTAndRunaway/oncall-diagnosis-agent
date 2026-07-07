# 重排序（Rerank）功能改动日志

> 日期：2026-07-07 | 基于方案：重排序方案沟通.md

---

## 改动文件总览

| 文件 | 总行数 | 新增行 | 删除行 |
|------|--------|--------|--------|
| `src/main/java/org/example/service/VectorSearchService.java` | 309 | +217 | -10 |
| `src/main/resources/application.yml` | 102 | +6 | 0 |
| **合计** | **411** | **+223** | **-10** |

---

## 1. VectorSearchService.java（309 行）

### 1.1 新增 import（9 行）
- `com.fasterxml.jackson.annotation.JsonProperty` — Rerank API 响应 JSON 反序列化
- `org.springframework.beans.factory.annotation.Value` — 配置注入
- `org.springframework.http.*` — REST API 调用（`RestTemplate`, `HttpHeaders`, `HttpEntity` 等）
- `java.util.stream.Collectors` — 文档过滤

### 1.2 新增配置字段（~18 行）
- `rerankEnabled` — 重排序开关（`rag.rerank.enabled`，默认 true）
- `rerankThreshold` — 触发阈值（`rag.rerank.threshold`，默认 10）
- `rerankTopK` — 重排序保留数（`rag.rerank.top-k`，默认 10）
- `rerankModel` — Rerank 模型名（`rag.rerank.model`，默认 `gte-rerank-v2`）
- `recallCount` — Milvus 初始召回数（`rag.recall-count`，默认 30）
- `dashscopeApiKey` — DashScope API Key（复用现有配置）
- `RestTemplate` — HTTP 客户端实例
- `RERANK_API_URL` — DashScope Rerank API 端点常量

### 1.3 修改 `searchSimilarDocuments()` 方法（~10 行改动）
- 检索时用 `recallCount` 替代 `topK`（开启重排序时）
- 新增第 5 步：结果数 > threshold 时调用 `rerank()`，否则按需截断

### 1.4 新增 `rerank()` 私有方法（~63 行）
- 过滤空内容文档
- 提取文档文本列表
- 调用 `callRerankApi()` 获取重排序结果
- 按 `relevance_score` 降序组装结果
- 结果不足时用原始文档补足
- 异常降级：返回原始排序前 N 条 + warn 日志

### 1.5 新增 `callRerankApi()` 私有方法（~30 行）
- 构建 DashScope Rerank REST API 请求体（model / input.query / input.documents / parameters.top_n）
- 设置 Bearer Token 认证头
- 通过 `RestTemplate.postForEntity()` 发送请求
- 空响应保护

### 1.6 新增 Rerank API 响应 DTO（4 个内部类，~35 行）
- `RerankResponse` — 顶层响应（output / usage / request_id）
- `RerankOutput` — 输出结果列表
- `RerankResultItem` — 单条结果（index / relevance_score）
- `RerankUsage` — Token 用量统计

### 1.7 `SearchResult` 新增字段（~3 行）
- `rerankScore`（Double）— 重排序相关性分数，未经过 rerank 时为 null

---

## 2. application.yml（102 行）

### 2.1 新增 `rag` 配置块（6 行）

```yaml
recall-count: 30            # Milvus 初始召回数量
rerank:
  enabled: true             # 重排序开关
  threshold: 10             # 触发阈值
  top-k: 10                 # 重排序后保留数
  model: "gte-rerank-v2"    # DashScope Rerank 模型
```

---

## 功能要点速查

| 场景 | 行为 |
|------|------|
| 召回 ≤ 10 条 | 不重排序，按 L2 分数截取返回 |
| 召回 > 10 条 | 调用 DashScope `gte-rerank-v2` API 重排序，取 top 10 |
| Rerank API 失败 | 降级：warn 日志 + 返回原始排序前 N 条 |
| `rerank.enabled=false` | 行为与改动前完全一致 |
| 文档内容为空 | 自动过滤，不影响其他文档重排序 |
