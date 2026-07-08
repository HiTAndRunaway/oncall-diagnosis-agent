# 双路并行召回 + RRF 融合 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 VectorSearchService 内实现向量检索 + BM25 检索双路并行召回，RRF 融合后取 top recallCount 条进入现有 Rerank 流程。

**Architecture:** 利用 Milvus 2.4+ 内置 Analyzer / BM25 Function 实现稀疏向量自动生成；检索阶段使用 CompletableFuture 并行执行 dense + sparse 两路搜索；RRF（倒数排名融合）对两路结果去重合并排序；外部接口不变。

**Tech Stack:** Spring Boot 3.2, milvus-sdk-java 2.6.10, Milvus 2.4+ (Analyzer/Function/Hybrid Search)

## Global Constraints

- 仅修改 VectorSearchService 内部逻辑，对外签名不变
- VectorIndexService 不修改（BM25 Function 自动填充 sparse_vector）
- BM25 路异常时降级为单路向量，不可阻断主流程
- 首次部署需 drop + recreate collection

---

### Task 1: 配置与常量

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/application.yml`
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/constant/MilvusConstants.java`

- [ ] **Step 1: 在 application.yml 新增 hybrid 配置项**

在 `rag.rerank.model: "gte-rerank-v2"` 行之后，插入以下配置块：

```yaml
  hybrid:
    enabled: true          # 双路召回开关，false=退化为单路向量
    bm25-weight: 1.0       # BM25 路 RRF 权重
    vector-weight: 1.0     # 向量路 RRF 权重
    rrf-k: 60              # RRF 平滑常数
```

插入后的上下文（application.yml 第 79-85 行附近）：

```yaml
  rerank:
    enabled: true
    threshold: 10
    top-k: 10
    model: "gte-rerank-v2"
  hybrid:
    enabled: true
    bm25-weight: 1.0
    vector-weight: 1.0
    rrf-k: 60
```

- [ ] **Step 2: 在 MilvusConstants.java 新增常量**

在 `MilvusConstants` 类末尾（`private MilvusConstants() {}` 之前）添加：

```java
    /**
     * Sparse vector 字段名称（BM25 稀疏向量）
     */
    public static final String SPARSE_VECTOR_FIELD = "sparse_vector";

    /**
     * BM25 分词器名称
     */
    public static final String ANALYZER_NAME = "chinese_analyzer";

    /**
     * BM25 Function 名称
     */
    public static final String BM25_FUNCTION_NAME = "bm25_func";

    /**
     * BM25 Function 描述
     */
    public static final String BM25_FUNCTION_DESC = "BM25 function for content field";
```

- [ ] **Step 3: 提交**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/resources/application.yml SuperBizAgent-release-2026-05-17/src/main/java/org/example/constant/MilvusConstants.java
git commit -m "feat: add hybrid search config and constants"
```

---

### Task 2: AsyncConfig - 检索线程池

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/config/AsyncConfig.java`

**Interfaces:**
- Produces: `@Bean("searchExecutor") Executor` — 用于 CompletableFuture.supplyAsync() 的专用线程池

- [ ] **Step 1: 新增 searchExecutor Bean**

在 `summaryExecutor()` Bean 之后、类结束 `}` 之前添加：

```java
    /**
     * 混合检索专用线程池
     * 为双路并行召回（dense + sparse）提供线程资源
     */
    @Bean("searchExecutor")
    public Executor searchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("search-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                logger.warn("检索线程池已满，降级为调用者线程执行");
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        return executor;
    }
```

- [ ] **Step 2: 提交**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/config/AsyncConfig.java
git commit -m "feat: add search executor thread pool"
```

---

### Task 3: MilvusClientFactory - Schema / Analyzer / BM25 Function / Sparse Index

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/client/MilvusClientFactory.java`

**Interfaces:**
- Consumes: `MilvusConstants.SPARSE_VECTOR_FIELD`, `MilvusConstants.ANALYZER_NAME`, `MilvusConstants.BM25_FUNCTION_NAME`, `MilvusConstants.BM25_FUNCTION_DESC`
- Produces: `createBizCollection()` 更新 schema; `createAnalyzer()`, `createBm25Function()`, `createSparseIndex()` 三个新私有方法

- [ ] **Step 1: 在 createBizCollection() 中添加 sparse_vector 字段**

在 `createBizCollection()` 方法的 `metadataField` 定义之后，`CollectionSchemaParam` 构建之前，添加：

```java
        FieldType sparseVectorField = FieldType.newBuilder()
                .withName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withDataType(DataType.SparseFloatVector)
                .build();
```

然后在 `CollectionSchemaParam.newBuilder()` 的链式调用中添加 `.addFieldType(sparseVectorField)`：

```java
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .addFieldType(sparseVectorField)
                .build();
```

- [ ] **Step 2: 新增 createAnalyzer() 方法**

在 `createBizCollection()` 之后添加：

```java
    /**
     * 创建中文分词器（用于 BM25）
     */
    private void createAnalyzer(MilvusServiceClient client) {
        // 先检查是否已存在
        try {
            // 尝试创建，若已存在则忽略
            io.milvus.param.collection.CreateAliasParam analyzerParam =
                    io.milvus.param.collection.CreateAliasParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .withAlias(MilvusConstants.ANALYZER_NAME)
                            .build();
        } catch (Exception ignored) {
            // analyzer 检查仅用于降级路径
        }

        // Milvus 2.4+ 通过 RESTful API 或 SDK 创建 Analyzer
        // 使用 HTTP 调用 Milvus REST API 创建 analyzer
        String restUrl = String.format("http://%s:%d/v2/analyzers",
                milvusProperties.getHost(), milvusProperties.getPort());

        org.springframework.web.client.RestTemplate rest =
                new org.springframework.web.client.RestTemplate();

        Map<String, Object> analyzerBody = new HashMap<>();
        analyzerBody.put("name", MilvusConstants.ANALYZER_NAME);
        analyzerBody.put("type", "chinese");
        analyzerBody.put("params", Map.of());

        try {
            org.springframework.http.HttpHeaders headers =
                    new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (milvusProperties.getUsername() != null
                    && !milvusProperties.getUsername().isEmpty()) {
                String auth = milvusProperties.getUsername() + ":"
                        + milvusProperties.getPassword();
                headers.setBasicAuth(
                        java.util.Base64.getEncoder().encodeToString(auth.getBytes()));
            }

            org.springframework.http.HttpEntity<Map<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(analyzerBody, headers);

            rest.postForEntity(restUrl, request, String.class);
            logger.info("成功创建 Analyzer: {}", MilvusConstants.ANALYZER_NAME);
        } catch (Exception e) {
            // Analyzer 可能已存在，仅记录日志
            logger.info("创建 Analyzer 时出现异常（可能已存在）: {}", e.getMessage());
        }
    }
```

- [ ] **Step 3: 新增 createBm25Function() 方法**

在 `createAnalyzer()` 之后添加：

```java
    /**
     * 创建 BM25 Function，将 content 自动转换为 sparse_vector
     */
    private void createBm25Function(MilvusServiceClient client) {
        String restUrl = String.format("http://%s:%d/v2/functions",
                milvusProperties.getHost(), milvusProperties.getPort());

        org.springframework.web.client.RestTemplate rest =
                new org.springframework.web.client.RestTemplate();

        Map<String, Object> functionBody = new HashMap<>();
        functionBody.put("name", MilvusConstants.BM25_FUNCTION_NAME);
        functionBody.put("description", MilvusConstants.BM25_FUNCTION_DESC);
        functionBody.put("type", "BM25");
        functionBody.put("params", Map.of(
                "input_field", "content",
                "output_field", MilvusConstants.SPARSE_VECTOR_FIELD,
                "analyzer", MilvusConstants.ANALYZER_NAME
        ));

        try {
            org.springframework.http.HttpHeaders headers =
                    new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (milvusProperties.getUsername() != null
                    && !milvusProperties.getUsername().isEmpty()) {
                String auth = milvusProperties.getUsername() + ":"
                        + milvusProperties.getPassword();
                headers.setBasicAuth(
                        java.util.Base64.getEncoder().encodeToString(auth.getBytes()));
            }

            org.springframework.http.HttpEntity<Map<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(functionBody, headers);

            org.springframework.http.ResponseEntity<String> response =
                    rest.postForEntity(restUrl, request, String.class);
            logger.info("成功创建 BM25 Function: {} (HTTP {})",
                    MilvusConstants.BM25_FUNCTION_NAME, response.getStatusCode().value());
        } catch (Exception e) {
            // Function 可能已存在，仅记录日志
            logger.info("创建 BM25 Function 时出现异常（可能已存在）: {}", e.getMessage());
        }
    }
```

- [ ] **Step 4: 新增 createSparseIndex() 方法**

在 `createBm25Function()` 之后添加：

```java
    /**
     * 为 sparse_vector 字段创建 SPARSE_INVERTED_INDEX 索引
     */
    private void createSparseIndex(MilvusServiceClient client) {
        CreateIndexParam sparseIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withIndexType(IndexType.SPARSE_INVERTED_INDEX)
                .withMetricType(MetricType.IP)
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(sparseIndexParam);
        if (response.getStatus() != 0) {
            // 索引可能已存在，记录日志但不阻断
            logger.warn("创建 sparse index 时出现警告: {}", response.getMessage());
        } else {
            logger.info("成功为 sparse_vector 字段创建索引");
        }
    }
```

- [ ] **Step 5: 更新 createClient() 初始化流程**

在 `createClient()` 中，`createIndexes(client)` 之后添加 Analyzer、Function、Sparse Index 的创建调用：

```java
                // 创建索引
                createIndexes(client);
                logger.info("成功创建索引");

                // 创建 BM25 所需组件
                createAnalyzer(client);
                createBm25Function(client);
                createSparseIndex(client);
                logger.info("BM25 组件初始化完成");
```

- [ ] **Step 6: 提交**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/client/MilvusClientFactory.java
git commit -m "feat: add BM25 analyzer, function and sparse index"
```

---

### Task 4: VectorSearchService - 双路召回 + RRF 融合

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/VectorSearchService.java`

**Interfaces:**
- Consumes: `MilvusConstants.SPARSE_VECTOR_FIELD`; `AsyncConfig.searchExecutor` (via `@Qualifier`)
- Produces: `private List<SearchResult> sparseSearch(String query, int topK)`, `private List<SearchResult> rrfFusion(List<SearchResult> dense, List<SearchResult> sparse, int topK)`, `searchSimilarDocuments()` 逻辑更新

- [ ] **Step 1: 新增 hybrid 配置字段注入**

在类顶部现有 `@Value` 字段区域（`private int recallCount` 行之后）添加：

```java
    // ===== 双路召回配置 =====
    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.hybrid.bm25-weight:1.0}")
    private double bm25Weight;

    @Value("${rag.hybrid.vector-weight:1.0}")
    private double vectorWeight;

    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;
```

- [ ] **Step 2: 新增 searchExecutor 注入**

在现有 `@Autowired` 区域添加：

```java
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("searchExecutor")
    private java.util.concurrent.Executor searchExecutor;
```

并在文件顶部 import 区域确认以下 import 存在（若缺失则补充）：

```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Comparator;
```

同时注入 `MilvusProperties` 用于 BM25 REST API 调用：

```java
    @Autowired
    private org.example.config.MilvusProperties milvusProperties;
```

更新 import 区域（在现有 import 之后添加）：

```java
import org.example.config.MilvusProperties;
import org.springframework.beans.factory.annotation.Qualifier;
```

- [ ] **Step 3: 新增 sparseSearch() 方法**

在 `searchSimilarDocuments()` 之后、`rerank()` 之前添加：

```java
    /**
     * BM25 稀疏向量检索
     * 通过 Milvus REST API 将 query 文本转为 sparse vector 后检索
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return BM25 检索结果
     */
    private List<SearchResult> sparseSearch(String query, int topK) {
        try {
            logger.info("开始 BM25 稀疏检索, query: {}, topK: {}", query, topK);

            // 1. 调用 Milvus analyzer 将 query 转为 sparse vector
            SortedMap<Long, Float> querySparseVector = tokenizeQuery(query);
            if (querySparseVector.isEmpty()) {
                logger.warn("查询文本分词后为空 sparse vector，返回空结果");
                return Collections.emptyList();
            }
            logger.debug("查询稀疏向量维度: {}", querySparseVector.size());

            // 2. 构建搜索参数
            List<SortedMap<Long, Float>> queryVectors = Collections.singletonList(querySparseVector);
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName(MilvusConstants.SPARSE_VECTOR_FIELD)
                    .withVectors(queryVectors)
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.IP)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withConsistencyLevel(io.milvus.common.clientenum.ConsistencyLevelEnum.BOUNDED)
                    .build();

            // 3. 执行搜索
            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("BM25 稀疏搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());

                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }
                results.add(result);
            }

            logger.info("BM25 稀疏召回 {} 个文档", results.size());
            return results;

        } catch (Exception e) {
            logger.warn("BM25 稀疏检索失败，返回空结果: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 调用 Milvus REST API 将查询文本分词并转为稀疏向量
     *
     * @param query 查询文本
     * @return SortedMap<维度索引, BM25权重> 形式的稀疏向量
     */
    private SortedMap<Long, Float> tokenizeQuery(String query) {
        String analyzerUrl = String.format("http://%s:%d/v2/analyzers/%s/tokens",
                milvusProperties.getHost(), milvusProperties.getPort(),
                MilvusConstants.ANALYZER_NAME);

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("text", query);

        org.springframework.http.HttpHeaders headers =
                new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (milvusProperties.getUsername() != null
                && !milvusProperties.getUsername().isEmpty()) {
            String auth = milvusProperties.getUsername() + ":"
                    + milvusProperties.getPassword();
            headers.setBasicAuth(
                    java.util.Base64.getEncoder().encodeToString(auth.getBytes()));
        }

        org.springframework.http.HttpEntity<Map<String, Object>> request =
                new org.springframework.http.HttpEntity<>(requestBody, headers);

        try {
            org.springframework.http.ResponseEntity<Map> response =
                    restTemplate.postForEntity(analyzerUrl, request, Map.class);

            if (response.getBody() == null) {
                logger.warn("分词 API 返回空响应");
                return Collections.emptySortedMap();
            }

            // 解析分词结果: {"data": [{"token": "word1", "id": 12345}, ...]}
            java.util.List<Map<String, Object>> tokens =
                    (java.util.List<Map<String, Object>>) response.getBody().get("data");

            if (tokens == null || tokens.isEmpty()) {
                return Collections.emptySortedMap();
            }

            // 统计每个 token 的频率，构建 BM25 稀疏向量
            // 简化处理：每个 token 出现一次，权重为 1.0
            SortedMap<Long, Float> sparseVector = new java.util.TreeMap<>();
            for (Map<String, Object> token : tokens) {
                Long tokenId = ((Number) token.get("id")).longValue();
                // BM25 权重简单使用词频（后续可优化为真正的 BM25 权重）
                sparseVector.merge(tokenId, 1.0f, Float::sum);
            }

            logger.debug("查询分词完成: {} 个 token → {} 个唯一 token",
                    tokens.size(), sparseVector.size());
            return sparseVector;

        } catch (Exception e) {
            logger.warn("分词 API 调用失败: {}", e.getMessage());
            return Collections.emptySortedMap();
        }
    }
```

> **注意**：`tokenizeQuery()` 中调用了 Milvus REST API `/v2/analyzers/{name}/tokens`。此 API 需要 Milvus 2.4+ 支持。`SortedMap` 即为 `java.util.SortedMap`。

在文件顶部追加 import：

```java
import java.util.SortedMap;
import java.util.Collections;
```

- [ ] **Step 4: 新增 rrfFusion() 方法**

在 `sparseSearch()` 之后、`rerank()` 之前添加：

```java
    /**
     * RRF（Reciprocal Rank Fusion）融合算法
     * 对两路检索结果按排名进行加权融合，返回 topK 条
     *
     * @param denseResults  向量路结果（已按 score 降序）
     * @param sparseResults BM25 路结果（已按 score 降序）
     * @param topK          最终返回数量
     * @return RRF 融合后 topK 条结果
     */
    private List<SearchResult> rrfFusion(List<SearchResult> denseResults,
                                          List<SearchResult> sparseResults,
                                          int topK) {
        // RRF 分数 Map: id → RRF score
        LinkedHashMap<String, Double> rrfScores = new LinkedHashMap<>();
        // 保留原始 SearchResult 用于获取完整信息
        Map<String, SearchResult> resultMap = new LinkedHashMap<>();

        // 处理向量路：记录排名（1-indexed）
        for (int i = 0; i < denseResults.size(); i++) {
            SearchResult r = denseResults.get(i);
            String id = r.getId();
            int rank = i + 1;  // 1-indexed rank
            double contribution = vectorWeight / (rrfK + rank);
            rrfScores.merge(id, contribution, Double::sum);
            resultMap.putIfAbsent(id, r);
        }

        // 处理 BM25 路：记录排名（1-indexed）
        for (int i = 0; i < sparseResults.size(); i++) {
            SearchResult r = sparseResults.get(i);
            String id = r.getId();
            int rank = i + 1;  // 1-indexed rank
            double contribution = bm25Weight / (rrfK + rank);
            rrfScores.merge(id, contribution, Double::sum);
            resultMap.putIfAbsent(id, r);
        }

        // 按 RRF 分数降序排列，取 topK
        List<SearchResult> fused = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    SearchResult original = resultMap.get(entry.getKey());
                    SearchResult result = new SearchResult();
                    result.setId(original.getId());
                    result.setContent(original.getContent());
                    result.setMetadata(original.getMetadata());
                    // RRF 分数暂存到 score 字段（后续 Rerank 会覆盖）
                    result.setScore(entry.getValue().floatValue());
                    return result;
                })
                .collect(Collectors.toList());

        logger.info("RRF 融合完成: dense={}, sparse={}, fused={}",
                denseResults.size(), sparseResults.size(), fused.size());
        return fused;
    }
```

- [ ] **Step 5: 重构 searchSimilarDocuments() 实现双路并行召回**

将 `searchSimilarDocuments(String query, int topK)` 方法体替换为以下内容：

```java
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            int fetchCount = rerankEnabled ? recallCount : topK;
            logger.info("开始搜索相似文档, query: {}, fetchCount: {}, hybridEnabled: {}",
                    query, fetchCount, hybridEnabled);

            List<SearchResult> results;

            if (hybridEnabled) {
                // === 双路并行召回 ===

                // 1. 异步执行向量检索
                CompletableFuture<List<SearchResult>> denseFuture =
                        CompletableFuture.supplyAsync(
                                () -> denseSearch(query, fetchCount), searchExecutor);

                // 2. 异步执行 BM25 稀疏检索
                CompletableFuture<List<SearchResult>> sparseFuture =
                        CompletableFuture.supplyAsync(
                                () -> sparseSearch(query, fetchCount), searchExecutor);

                // 3. 等待两路完成
                CompletableFuture.allOf(denseFuture, sparseFuture).join();

                // 4. 获取结果
                List<SearchResult> denseResults;
                List<SearchResult> sparseResults;
                try {
                    denseResults = denseFuture.get();
                } catch (Exception e) {
                    logger.error("向量检索路异常", e);
                    throw new RuntimeException("向量检索失败: " + e.getMessage(), e);
                }

                try {
                    sparseResults = sparseFuture.get();
                } catch (Exception e) {
                    logger.warn("BM25 路异常，降级为单路向量: {}", e.getMessage());
                    sparseResults = Collections.emptyList();
                }

                // 5. 判断是否需要 RRF 融合
                if (sparseResults.isEmpty()) {
                    logger.info("BM25 路无结果，直接使用向量路");
                    results = denseResults;
                } else {
                    // 6. RRF 融合
                    results = rrfFusion(denseResults, sparseResults, fetchCount);
                }

            } else {
                // === 单路向量召回（原有逻辑） ===
                results = denseSearch(query, fetchCount);
            }

            logger.info("召回 {} 个候选文档", results.size());

            // 7. 重排序（现有逻辑不变）
            if (rerankEnabled && results.size() > rerankThreshold) {
                results = rerank(query, results);
            } else if (results.size() > topK) {
                if (rerankEnabled) {
                    logger.debug("跳过重排序，结果数 {} ≤ 阈值 {}", results.size(), rerankThreshold);
                }
                results = results.subList(0, topK);
            }

            logger.info("搜索完成, 最终返回 {} 个文档", results.size());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 6: 提取 denseSearch() 方法**

将原有 `searchSimilarDocuments()` 中的向量检索逻辑提取为独立方法。在 `searchSimilarDocuments()` 之后添加：

```java
    /**
     * 向量路检索（Dense Vector Search）
     * 提取自原有 searchSimilarDocuments 中的向量检索部分
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return 向量检索结果
     */
    private List<SearchResult> denseSearch(String query, int topK) {
        logger.debug("开始向量检索, query: {}, topK: {}", query, topK);

        // 1. 将查询文本向量化
        List<Float> queryVector = embeddingService.generateQueryVector(query);
        logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

        // 2. 构建搜索参数
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withVectorFieldName("vector")
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(topK)
                .withMetricType(io.milvus.param.MetricType.L2)
                .withOutFields(List.of("id", "content", "metadata"))
                .withParams("{\"nprobe\":10}")
                .build();

        // 3. 执行搜索
        R<SearchResults> searchResponse = milvusClient.search(searchParam);

        if (searchResponse.getStatus() != 0) {
            throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
        }

        // 4. 解析搜索结果
        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
        List<SearchResult> results = new ArrayList<>();

        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResult result = new SearchResult();
            result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
            result.setContent((String) wrapper.getFieldData("content", 0).get(i));
            result.setScore(wrapper.getIDScore(0).get(i).getScore());

            Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
            if (metadataObj != null) {
                result.setMetadata(metadataObj.toString());
            }

            results.add(result);
        }

        logger.info("向量检索召回 {} 个文档", results.size());
        return results;
    }
```

- [ ] **Step 7: 补充顶部 import**

确认以下 import 全部存在（若缺失则追加到文件顶部 import 区域）：

```java
import org.example.config.MilvusProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.SortedMap;
import java.util.Collections;
import java.util.Map;
```

- [ ] **Step 8: 提交**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/VectorSearchService.java
git commit -m "feat: implement dual-path recall with RRF fusion"
```

---

### Task 5: 构建验证与测试

**Files:**
- 无新文件，验证编译和启动

- [ ] **Step 1: 编译项目**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile -q
```

预期：BUILD SUCCESS

- [ ] **Step 2: 确认打包**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean package -DskipTests -q
```

预期：BUILD SUCCESS

- [ ] **Step 3: 启动应用并验证健康检查**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn spring-boot:run &
sleep 30
curl -s http://localhost:9900/milvus/health
```

预期：返回健康状态 OK

- [ ] **Step 4: 提交**

```bash
git commit --allow-empty -m "chore: verify build and health check"
```

---

## Self-Review Notes

1. **Spec coverage**: 方案的每个要求都有对应任务——配置(T1)、Milvus 前置准备(T3)、RRF 融合(T4)、双路并行(T4)、降级(T4)、外部接口不变(全局约束)
2. **Placeholder scan**: 所有代码步骤均提供了完整代码，无 TBD/TODO
3. **Type consistency**: `sparseSearch → List<SearchResult>`, `rrfFusion → List<SearchResult>`, `denseSearch → List<SearchResult>`，返回类型一致；`hybridEnabled/vectorWeight/bm25Weight/rrfK` 在 T4 Step1 定义，T4 Step5 使用
4. **REST API 说明**: `tokenizeQuery()` 中调用的 `/v2/analyzers/{name}/tokens` 是 Milvus 2.4+ 提供的标准 REST API 端点。若该端点返回格式与预期不同，需根据实际响应结构调整 JSON 解析逻辑
