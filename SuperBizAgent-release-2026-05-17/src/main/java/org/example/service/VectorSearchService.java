package org.example.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Value("${dashscope.api.key}")
    private String dashscopeApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    // DashScope Rerank API 端点
    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            // 开启重排序时用 recallCount 召回更多候选，否则直接用 topK
            int fetchCount = rerankEnabled ? recallCount : topK;
            logger.info("开始搜索相似文档, 查询: {}, fetchCount: {}, rerankEnabled: {}", query, fetchCount, rerankEnabled);

            // 1. 将查询文本向量化
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(fetchCount)
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

            logger.info("Milvus 召回 {} 个相似文档", results.size());

            // 5. 重排序判断
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
     * 调用 DashScope Rerank REST API
     */
    private RerankResponse callRerankApi(String query, List<String> documents) {
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
