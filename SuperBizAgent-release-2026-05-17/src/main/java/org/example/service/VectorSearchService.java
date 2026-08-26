package org.example.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.config.LiteLlmProperties;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.example.service.multiquery.MultiQueryExpander;
import org.example.service.multiquery.QueryVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    @Qualifier("searchExecutor")
    private Executor searchExecutor;

    @Autowired
    private MilvusProperties milvusProperties;

    @Autowired
    private LiteLlmProperties liteLlmProperties;

    @Autowired(required = false)
    private MultiQueryExpander multiQueryExpander;

    // ===== 多角度查询路配置（Road C） =====
    @Value("${rag.multi-query.enabled:false}")
    private boolean multiQueryEnabled;

    @Value("${rag.multi-query.weight:1.0}")
    private double multiQueryWeight;

    @Value("${rag.multi-query.variant-recall-count:10}")
    private int variantRecallCount;

    @Value("${rag.multi-query.variant-top-k:30}")
    private int variantTopK;

    @Value("${rag.multi-query.variant-search-mode:dense}")
    private String variantSearchMode;

    @Value("${rag.multi-query.variant-rrf-k:60}")
    private int variantRrfK;

    // ===== 重排序配置 =====
    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.threshold:10}")
    private int rerankThreshold;

    @Value("${rag.rerank.top-k:10}")
    private int rerankTopK;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String rerankModel;

    @Value("${rag.recall-count:30}")
    private int recallCount;

    // ===== 双路召回配置 =====
    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.hybrid.bm25-weight:1.0}")
    private double bm25Weight;

    @Value("${rag.hybrid.vector-weight:1.0}")
    private double vectorWeight;

    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${dashscope.api.key}")
    private String dashscopeApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    // DashScope Rerank API 端点
    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /**
     * 搜索相似文档（支持双路并行召回 + RRF 融合）
     *
     * @param query 查询文本
     * @param topK  返回最相似的K个结果
     * @return 搜索结果列表
     */
    @CircuitBreaker(name = "milvus-search", fallbackMethod = "searchFallback")
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            int fetchCount = rerankEnabled ? recallCount : topK;
            logger.info("开始搜索相似文档, query: {}, fetchCount: {}, hybridEnabled: {}",
                    query, fetchCount, hybridEnabled);

            List<SearchResult> results;

            if (hybridEnabled) {
                // === 三路并行召回（dense + sparse + 多角度查询） ===

                // 1. 异步执行向量检索（必须成功）
                CompletableFuture<List<SearchResult>> denseFuture =
                        CompletableFuture.supplyAsync(
                                () -> denseSearch(query, fetchCount), searchExecutor);

                // 2. 异步执行 BM25 稀疏检索（可降级路径）
                CompletableFuture<List<SearchResult>> sparseFuture =
                        CompletableFuture.supplyAsync(
                                () -> sparseSearch(query, fetchCount), searchExecutor);

                // 3. 异步执行多角度查询检索（可选路，全有或全无：失败整路放弃）
                CompletableFuture<List<SearchResult>> multiQueryFuture = null;
                if (multiQueryEnabled && multiQueryExpander != null) {
                    multiQueryFuture = CompletableFuture.supplyAsync(
                            () -> multiQuerySearch(query), searchExecutor);
                }

                // 4. 等待密集路完成（必须成功）
                List<SearchResult> denseResults;
                try {
                    denseResults = denseFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.error("向量检索路异常", e);
                    throw new RuntimeException("向量检索失败: " + e.getMessage(), e);
                }

                // 5. 等待稀疏路完成（可降级路径）
                List<SearchResult> sparseResults;
                try {
                    sparseResults = sparseFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.warn("BM25 路异常，降级为单路向量: {}", e.getMessage());
                    sparseResults = Collections.emptyList();
                }

                // 6. 等待多角度路完成（内部已整路降级为空列表，此处仅兜底）
                List<SearchResult> multiQueryResults = Collections.emptyList();
                if (multiQueryFuture != null) {
                    try {
                        multiQueryResults = multiQueryFuture.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        logger.warn("多角度查询路异常，降级为两路召回: {}", e.getMessage());
                        multiQueryResults = Collections.emptyList();
                    }
                }

                // 7. 多路 RRF 融合（空路自然不贡献分数）
                List<List<SearchResult>> resultLists = new ArrayList<>();
                List<Double> weights = new ArrayList<>();
                resultLists.add(denseResults);
                weights.add(vectorWeight);
                if (!sparseResults.isEmpty()) {
                    resultLists.add(sparseResults);
                    weights.add(bm25Weight);
                }
                if (!multiQueryResults.isEmpty()) {
                    resultLists.add(multiQueryResults);
                    weights.add(multiQueryWeight);
                }
                results = rrfFusion(resultLists, weights, rrfK, fetchCount);

            } else {
                // === 单路向量召回（原有逻辑） ===
                results = denseSearch(query, fetchCount);
            }

            logger.info("召回 {} 个候选文档", results.size());

            // 8. 重排序（现有逻辑不变）
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

        // 处理 parent-child 策略的 small-to-big 检索
        results = resolveParentContent(results);

        return results;
    }

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

            // 处理 parent-child 策略的 small-to-big 检索
            results = resolveParentContent(results);

            return results;

        } catch (Exception e) {
            logger.warn("BM25 稀疏检索失败，返回空结果: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 多角度查询路（Road C）
     * <p>
     * 1. 通过 MultiQueryExpander 生成 {@code max-variants} 个角度变体（不含原始查询）；
     * 2. 每个变体并行独立检索（默认 dense，可配 hybrid）；
     * 3. 变体间等权 RRF 融合，取前 variant-top-k 条作为该路结果。
     * <p>
     * 全有或全无（all-or-nothing）：变体生成失败、任一变体检索失败、
     * 融合异常等任何环节失败，均返回空列表（不抛异常），由调用方降级为两路召回。
     *
     * @param query 改写后的查询文本（用于生成变体）
     * @return 多角度路召回结果；失败返回空列表
     */
    private List<SearchResult> multiQuerySearch(String query) {
        // 1. 生成变体（空列表/异常 = 该路放弃）
        List<QueryVariant> variants;
        try {
            variants = multiQueryExpander.expand(query);
        } catch (Exception e) {
            logger.warn("[MultiQuery] 变体生成异常，多角度路放弃: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (variants == null || variants.isEmpty()) {
            logger.warn("[MultiQuery] 无可用查询变体，多角度路放弃");
            return Collections.emptyList();
        }

        // 2. 变体并行检索（任一变体检索失败 → 整路放弃）
        List<CompletableFuture<List<SearchResult>>> futures = new ArrayList<>();
        for (QueryVariant variant : variants) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> searchByVariant(variant), searchExecutor));
        }

        List<List<SearchResult>> variantResults = new ArrayList<>();
        try {
            for (CompletableFuture<List<SearchResult>> future : futures) {
                variantResults.add(future.get(30, TimeUnit.SECONDS));
            }
        } catch (Exception e) {
            logger.warn("[MultiQuery] 变体检索失败，多角度路整路放弃: {}", e.getMessage());
            return Collections.emptyList();
        }

        // 过滤无结果的变体（合法空结果不算失败，直接跳过）
        variantResults.removeIf(List::isEmpty);
        if (variantResults.isEmpty()) {
            logger.warn("[MultiQuery] 所有变体均无检索结果，多角度路放弃");
            return Collections.emptyList();
        }

        // 3. 变体间等权 RRF 融合，取前 variantTopK 条
        List<Double> equalWeights = Collections.nCopies(variantResults.size(), 1.0);
        List<SearchResult> fused = rrfFusion(variantResults, equalWeights, variantRrfK, variantTopK);

        logger.info("[MultiQuery] 多角度路召回: variants={}, 有效变体={}, 融合后={}",
                variants.size(), variantResults.size(), fused.size());
        return fused;
    }

    /**
     * 单个变体的检索（dense 或 hybrid，由 variant-search-mode 配置决定）
     */
    private List<SearchResult> searchByVariant(QueryVariant variant) {
        List<SearchResult> results = denseSearch(variant.query(), variantRecallCount);
        if ("hybrid".equalsIgnoreCase(variantSearchMode)) {
            List<SearchResult> sparse = sparseSearch(variant.query(), variantRecallCount);
            if (!sparse.isEmpty()) {
                results = rrfFusion(List.of(results, sparse),
                        List.of(1.0, 1.0), variantRrfK, variantRecallCount);
            }
        }
        logger.debug("[MultiQuery] 变体检索完成: angle={}, query=[{}], results={}",
                variant.angle(), variant.query(), results.size());
        return results;
    }

    /**
     * 调用 Milvus REST API 将查询文本分词并转为稀疏向量
     *
     * @param query 查询文本
     * @return SortedMap<维度索引, BM25权重> 形式的稀疏向量
     */
    private SortedMap<Long, Float> tokenizeQuery(String query) {
        // Milvus 2.4+ RESTful API 端口为 9091（独立于 gRPC 端口 19530）
        String analyzerUrl = String.format("http://%s:%d/v2/analyzers/%s/tokens",
                milvusProperties.getHost(), 9091,
                MilvusConstants.ANALYZER_NAME);

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("text", query);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (milvusProperties.getUsername() != null
                && !milvusProperties.getUsername().isEmpty()) {
            String auth = milvusProperties.getUsername() + ":"
                    + milvusProperties.getPassword();
            headers.setBasicAuth(
                    java.util.Base64.getEncoder().encodeToString(auth.getBytes()));
        }

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(analyzerUrl, request, Map.class);

            if (response.getBody() == null) {
                logger.warn("分词 API 返回空响应");
                return Collections.emptySortedMap();
            }

            // 解析分词结果: {"data": [{"token": "word1", "id": 12345}, ...]}
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tokens =
                    (List<Map<String, Object>>) response.getBody().get("data");

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

    /**
     * RRF（Reciprocal Rank Fusion）融合算法（多路通用版）
     * 对多路检索结果按排名进行加权融合，返回 topK 条。
     * 空路（空列表）自然不贡献分数；权重缺失时按 1.0 处理。
     *
     * @param resultLists 各路检索结果列表（每路已按 score 降序）
     * @param weights     各路权重（长度可与 resultLists 不同，缺失按 1.0）
     * @param k           RRF 平滑常数
     * @param topK        最终返回数量
     * @return RRF 融合后 topK 条结果
     */
    private List<SearchResult> rrfFusion(List<List<SearchResult>> resultLists,
                                          List<Double> weights,
                                          int k,
                                          int topK) {
        if (resultLists == null || resultLists.isEmpty()) {
            return new ArrayList<>();
        }

        // RRF 分数 Map: id → RRF score
        LinkedHashMap<String, Double> rrfScores = new LinkedHashMap<>();
        // 保留原始 SearchResult 用于获取完整信息
        Map<String, SearchResult> resultMap = new LinkedHashMap<>();

        // 遍历每一路：记录排名（1-indexed）
        for (int road = 0; road < resultLists.size(); road++) {
            List<SearchResult> results = resultLists.get(road);
            if (results == null || results.isEmpty()) {
                continue;
            }
            double weight = (weights != null && road < weights.size()) ? weights.get(road) : 1.0;

            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                if (r == null || r.getId() == null) {
                    continue;
                }
                String id = r.getId();
                int rank = i + 1;  // 1-indexed rank
                double contribution = weight / (k + rank);
                rrfScores.merge(id, contribution, Double::sum);
                resultMap.putIfAbsent(id, r);
            }
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

        logger.info("RRF 融合完成: roads={}, fused={}", resultLists.size(), fused.size());
        return fused;
    }

    /**
     * 解析 parent-child 策略的检索结果
     * 检测 strategy == "parent-child" 时，将 content 替换为 parentContent，按 parentId 去重
     */
    private List<SearchResult> resolveParentContent(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return results;

        Set<String> seenParentIds = new HashSet<>();
        List<SearchResult> resolved = new ArrayList<>();

        for (SearchResult r : results) {
            if (r.getMetadata() == null || r.getMetadata().isEmpty()) {
                resolved.add(r);
                continue;
            }

            try {
                java.util.Map<String, Object> meta = parseMetadata(r.getMetadata());
                if (!"parent-child".equals(meta.get("strategy"))) {
                    resolved.add(r);
                    continue;
                }

                // parent-child 策略：去重 + 替换为 parent content
                String parentId = (String) meta.get("parentId");
                if (parentId != null && !parentId.isEmpty()) {
                    if (seenParentIds.contains(parentId)) {
                        logger.debug("parent-child 去重: parentId={}", parentId);
                        continue;
                    }
                    seenParentIds.add(parentId);
                }

                String parentContent = (String) meta.get("parentContent");
                if (parentContent != null && !parentContent.isEmpty()) {
                    logger.debug("parent-child 替换: child content ({} 字符) → parent content ({} 字符)",
                            r.getContent() != null ? r.getContent().length() : 0,
                            parentContent.length());
                    r.setContent(parentContent);
                } else {
                    logger.warn("parent-child 策略未找到 parentContent，降级使用 child content");
                }

            } catch (Exception e) {
                logger.warn("解析 parent-child metadata 失败，保留原始 content: {}", e.getMessage());
            }

            resolved.add(r);
        }

        if (resolved.size() < results.size()) {
            logger.info("parent-child 去重: {} → {} 条结果", results.size(), resolved.size());
        }
        return resolved;
    }

    /**
     * 将 metadata JSON 字符串解析为 Map
     */
    private java.util.Map<String, Object> parseMetadata(String metadataJson) {
        Gson gson = new Gson();
        return gson.fromJson(metadataJson,
                new TypeToken<java.util.Map<String, Object>>() {}.getType());
    }

    /**
     * 调用 DashScope Rerank API 对召回文档进行精确重排序
     *
     * @param query     原始查询文本
     * @param documents Milvus 召回的文档列表
     * @return 重排序后的结果（取 top rerankTopK 条）
     */
    private List<SearchResult> rerank(String query, List<SearchResult> documents) {
        try {
            logger.info("触发重排序，召回: {}, 阈值: {}, 模型: {}", documents.size(), rerankThreshold, rerankModel);

            // 过滤掉空内容文档
            List<SearchResult> validDocs = documents.stream()
                    .filter(d -> d.getContent() != null && !d.getContent().isEmpty())
                    .collect(Collectors.toList());

            if (validDocs.isEmpty()) {
                logger.warn("所有文档内容为空，跳过重排序");
                return documents.subList(0, Math.min(rerankTopK, documents.size()));
            }

            // 提取文档文本列表
            List<String> docTexts = validDocs.stream()
                    .map(SearchResult::getContent)
                    .collect(Collectors.toList());

            // 调用 DashScope Rerank REST API
            RerankResponse rerankResponse = callRerankApi(query, docTexts);

            // 按 relevance_score 降序排列，构建结果列表
            List<SearchResult> reranked = new ArrayList<>();

            for (RerankResultItem item : rerankResponse.output.results) {
                if (item.index >= 0 && item.index < validDocs.size()) {
                    SearchResult original = validDocs.get(item.index);
                    SearchResult result = new SearchResult();
                    result.setId(original.getId());
                    result.setContent(original.getContent());
                    result.setMetadata(original.getMetadata());
                    result.setScore(original.getScore());
                    result.setRerankScore(item.relevanceScore);
                    reranked.add(result);

                    if (reranked.size() >= rerankTopK) {
                        break;
                    }
                }
            }

            // 如果重排序返回不足，用原始结果补足
            if (reranked.size() < rerankTopK) {
                for (SearchResult doc : validDocs) {
                    if (reranked.size() >= rerankTopK) break;
                    boolean alreadyIncluded = reranked.stream()
                            .anyMatch(r -> r.getId().equals(doc.getId()));
                    if (!alreadyIncluded) {
                        reranked.add(doc);
                    }
                }
            }

            logger.info("重排序完成，返回: {} 条", reranked.size());
            return reranked;

        } catch (Exception e) {
            // 降级：返回原始排序的前 N 条
            logger.warn("Rerank API 调用失败，降级使用原始排序: {}", e.getMessage());
            int limit = Math.min(rerankTopK, documents.size());
            return new ArrayList<>(documents.subList(0, limit));
        }
    }

    /**
     * 调用 Rerank REST API
     * litellm.enabled=true 时走 liteLLM OpenAI 兼容 /v1/rerank，否则走 DashScope 原生 Rerank API
     */
    private RerankResponse callRerankApi(String query, List<String> documents) {
        if (liteLlmProperties.isEnabled()) {
            return callRerankApiGateway(query, documents);
        }

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", rerankModel);

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        requestBody.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", rerankTopK);
        parameters.put("return_documents", false);
        requestBody.put("parameters", parameters);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(dashscopeApiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        logger.debug("调用 DashScope Rerank API, 文档数: {}", documents.size());

        ResponseEntity<RerankResponse> response = restTemplate.postForEntity(
                RERANK_API_URL, entity, RerankResponse.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Rerank API 返回空响应");
        }

        return response.getBody();
    }

    /**
     * 通过 liteLLM 网关（OpenAI 兼容 /v1/rerank）调用重排序
     * <p>
     * 响应兼容两种形态：liteLLM/OpenAI 兼容的顶层 {@code results[]}，
     * 以及部分实现透传的 DashScope 风格 {@code output.results[]}。
     */
    @SuppressWarnings("unchecked")
    private RerankResponse callRerankApiGateway(String query, List<String> documents) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", rerankModel);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", rerankTopK);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(liteLlmProperties.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        String url = liteLlmProperties.getBaseUrl() + "/v1/rerank";

        logger.debug("调用 liteLLM Rerank 网关: {}, 文档数: {}", url, documents.size());

        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("liteLLM Rerank 网关返回空响应");
        }

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        if (results == null) {
            Map<String, Object> output = (Map<String, Object>) response.getBody().get("output");
            if (output != null) {
                results = (List<Map<String, Object>>) output.get("results");
            }
        }
        if (results == null || results.isEmpty()) {
            throw new RuntimeException("liteLLM Rerank 网关返回无 results");
        }

        RerankResponse rerankResponse = new RerankResponse();
        RerankOutput rerankOutput = new RerankOutput();
        List<RerankResultItem> items = new ArrayList<>();
        for (Map<String, Object> r : results) {
            RerankResultItem item = new RerankResultItem();
            // 解析失败显式抛异常，由上层 rerank() 降级为原始排序，避免产出脏结果（如全部 index 归 0）
            item.setIndex(parseIntField(r.get("index")));
            item.setRelevanceScore(parseDoubleField(r.get("relevance_score")));
            items.add(item);
        }
        rerankOutput.setResults(items);
        rerankResponse.setOutput(rerankOutput);
        return rerankResponse;
    }

    /**
     * 解析 rerank 响应中的 index 字段（支持 Number 与数字字符串）。
     */
    private static int parseIntField(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to error below
            }
        }
        throw new IllegalArgumentException("liteLLM Rerank 网关返回的 index 字段无法解析: " + value);
    }

    /**
     * 解析 rerank 响应中的 relevance_score 字段（支持 Number 与数字字符串）。
     */
    private static double parseDoubleField(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to error below
            }
        }
        throw new IllegalArgumentException("liteLLM Rerank 网关返回的 relevance_score 字段无法解析: " + value);
    }

    // ===== Rerank API 响应 DTO =====

    /**
     * DashScope Rerank API 响应体
     */
    @Setter
    @Getter
    private static class RerankResponse {
        @JsonProperty("output")
        private RerankOutput output;

        @JsonProperty("usage")
        private RerankUsage usage;

        @JsonProperty("request_id")
        private String requestId;
    }

    @Setter
    @Getter
    private static class RerankOutput {
        @JsonProperty("results")
        private List<RerankResultItem> results;
    }

    @Setter
    @Getter
    private static class RerankResultItem {
        @JsonProperty("index")
        private int index;

        @JsonProperty("relevance_score")
        private double relevanceScore;
    }

    @Setter
    @Getter
    private static class RerankUsage {
        @JsonProperty("total_tokens")
        private int totalTokens;
    }

    /**
     * Milvus 搜索断路器降级方法
     * 当断路器打开或搜索异常时，返回空列表，使上层 hybridSearch 退化为纯 BM25 + 重排序
     */
    private List<SearchResult> searchFallback(String query, int topK, Throwable t) {
        logger.warn("[CircuitBreaker] Milvus 搜索降级 - 返回空列表, query: {}, error: {}", query, t.getMessage());
        return Collections.emptyList();
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        /** 重排序相关性分数（仅经过 rerank 时有值，原始 L2 score 保留在 score 字段） */
        private Double rerankScore;

    }
}
