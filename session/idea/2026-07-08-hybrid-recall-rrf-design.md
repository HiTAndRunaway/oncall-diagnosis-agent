# 双路并行召回 + RRF 融合优化方案

> 日期：2026-07-08
> 状态：设计完成，待评审

---

## 1. 背景与目标

当前 RAG 召回管线仅使用**单路向量检索**（DashScope text-embedding-v4 dense embedding → Milvus IVF_FLAT L2 搜索）。向量检索擅长捕捉语义相似性，但对关键词精确匹配不敏感，可能导致某些包含关键术语但与查询语义向量不够"近"的高价值 chunk 被遗漏。

本方案引入 **BM25 稀疏检索作为第二路**，与现有向量检索并行执行，通过 **RRF（Reciprocal Rank Fusion）** 融合两路结果，提升召回覆盖率和排序质量。

**改造范围**：仅限 `VectorSearchService` 内部，对外接口签名和行为不变。`InternalDocsTools`、`RagService` 等调用方无感知。

---

## 2. 整体架构

### 2.1 召回流程

```
query
  ├─→ 向量检索（dense, IVF_FLAT + L2） ──→ recallCount 条
  ├─→ BM25 检索（sparse, SPARSE_INVERTED_INDEX + IP）──→ recallCount 条
         ↓
      RRF 融合 ← recallCount × 2
         ↓
    Top recallCount 条
         ↓
   DashScope Rerank（现有逻辑不变）
         ↓
    Top rerankTopK / topK
```

### 2.2 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rag.hybrid.enabled` | `true` | 双路召回开关，false 则退化单路向量 |
| `rag.hybrid.bm25-weight` | `1.0` | BM25 路在 RRF 中的权重 |
| `rag.hybrid.vector-weight` | `1.0` | 向量路在 RRF 中的权重 |
| `rag.hybrid.rrf-k` | `60` | RRF 平滑常数 |
| `rag.recall-count` | `30` | 每路召回数量（两路各取 N 条，RRF 从中取 N 条） |

---

## 3. Milvus 前置准备

### 3.1 Collection Schema 变更

当前 `biz` collection（`id` / `vector` / `content` / `metadata`）新增一个字段：

| 新字段 | 类型 | 说明 |
|--------|------|------|
| `sparse_vector` | `SparseFloatVector` | BM25 稀疏向量，由 Milvus Function 自动生成 |

> **破坏性变更**：Milvus 不支持直接给已有 collection 添加字段，首次部署需 drop + recreate collection，存量数据重新入库。

### 3.2 Analyzer（中文分词器）

```text
analyzer name:  chinese_analyzer
type:           chinese（Milvus 内置中文分词）
```

### 3.3 BM25 Function

```text
function name:   bm25_func
type:            BM25
input field:     content
output field:    sparse_vector
```

此后每次插入 chunk，`sparse_vector` 由 Function 自动计算。`VectorIndexService.insertToMilvus()` **无需改动**——只需照常传入 `id`/`content`/`vector`/`metadata`，`sparse_vector` 自动填充。

### 3.4 Sparse Vector 索引

```text
字段:        sparse_vector
索引类型:    SPARSE_INVERTED_INDEX
度量类型:    IP（内积，BM25 score 通过 IP 方式实现）
```

### 3.5 初始化流程

`MilvusClientFactory.createClient()` 中新增检测逻辑：

1. 检测 collection 是否存在
2. 若存在但无 `sparse_vector` 字段 → drop 后按新 schema 重建
3. 创建/重建后依次：Analyzer → Function → Dense Index → Sparse Index

---

## 4. RRF 融合算法

### 4.1 公式

```
RRF_score(d) = Σ  w_i / (k + rank_i(d))
```

- `d`：候选 chunk（以 Milvus `id` 字段为唯一键去重）
- `i`：检索通路（向量路 / BM25 路）
- `rank_i(d)`：chunk 在第 i 路中的排名，1-indexed（排名 1 = 最佳）
- `w_i`：第 i 路的权重（`vector-weight` / `bm25-weight`）
- `k`：平滑常数（`rrf-k`，默认 60）

### 4.2 边界处理

| 场景 | 处理 |
|------|------|
| chunk 仅在向量路出现 | BM25 路 rank = +∞，该项贡献为 0 |
| chunk 仅在 BM25 路出现 | 对称处理 |
| chunk 两路均出现 | 两路 rank 分别代入公式求和 |

融合后按 RRF 分数**降序排列**，取前 `recallCount` 条进入 Rerank。

---

## 5. 双路检索实现

### 5.1 改造点

仅修改 `VectorSearchService.searchSimilarDocuments()` 内部逻辑。

### 5.2 Dense 路（向量检索）

与现有逻辑完全一致：

```text
query → VectorEmbeddingService.generateQueryVector(query)
      → Milvus search(vector, L2, nprobe=10, topK=recallCount)
      → List<SearchResult>（附带 score）
```

### 5.3 Sparse 路（BM25 检索）

新增逻辑：

```text
query（原文）
  → Milvus 通过 chinese_analyzer 自动分词
  → search(sparse_vector, IP, topK=recallCount)
  → List<SearchResult>（附带 score）
```

### 5.4 并发调度

```java
CompletableFuture<List<SearchResult>> denseFuture =
    CompletableFuture.supplyAsync(() -> denseSearch(query));
CompletableFuture<List<SearchResult>> sparseFuture =
    CompletableFuture.supplyAsync(() -> sparseSearch(query));

CompletableFuture.allOf(denseFuture, sparseFuture).join();

List<SearchResult> denseResults = denseFuture.get();
List<SearchResult> sparseResults = sparseFuture.get();
```

### 5.5 异常降级

| 场景 | 行为 |
|------|------|
| 两路正常 | RRF 融合 |
| BM25 路超时/异常 | warn 日志 → 降级为单路向量 → 继续 Rerank |
| Dense 路异常 | 直接抛出（向量路是核心，不可降级） |
| `rag.hybrid.enabled=false` | 完全跳过 BM25 + RRF，走现有单路逻辑 |
| RRF 计算异常 | 降级为向量路原始排序截断 |
| 两路均无结果 | 返回空列表，上层照常处理 |

---

## 6. 配置汇总

`application.yml` 新增：

```yaml
rag:
  hybrid:
    enabled: true          # 双路召回开关
    bm25-weight: 1.0       # BM25 路 RRF 权重
    vector-weight: 1.0     # 向量路 RRF 权重
    rrf-k: 60              # RRF 平滑常数
```

代码中通过 `@Value` 注入到 `VectorSearchService`，格式：

```java
@Value("${rag.hybrid.enabled:true}")   private boolean hybridEnabled;
@Value("${rag.hybrid.bm25-weight:1.0}") private double bm25Weight;
@Value("${rag.hybrid.vector-weight:1.0}") private double vectorWeight;
@Value("${rag.hybrid.rrf-k:60}")       private int rrfK;
```

---

## 7. 文件改动清单

| 文件 | 改动内容 |
|------|----------|
| `application.yml` | 新增 `rag.hybrid.*` 配置项 |
| `MilvusClientFactory.java` | 新增 Analyzer、BM25 Function、Sparse Index 创建；collection schema 增加 `sparse_vector` 字段 |
| `MilvusConstants.java` | 新增常量（analyzer 名称、function 名称等） |
| `VectorSearchService.java` | 新增 BM25 检索方法、RRF 融合方法、并发调度逻辑、降级处理 |
| `VectorIndexService.java` | **无需改动**（Function 自动计算 sparse_vector） |

---

## 8. 测试要点

1. **双路召回功能**：单路向量召回的 chunk 集合 vs 双路 RRF 融合后的 chunk 集合，验证后者在召回覆盖面和排序合理性上不劣于前者
2. **降级测试**：关闭 hybrid 开关、Milvus BM25 不可用时，自动退化为单路向量
3. **并发正确性**：多线程环境下搜索无数据竞争
4. **存量兼容**：重建 collection 后存量文档重新入库，搜索正常
5. **权重调节**：修改 `vector-weight` / `bm25-weight` 影响 RRF 排序结果
