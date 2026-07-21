package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.example.config.MemoryProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆管理器
 * 负责长期记忆的 CRUD 操作，基于 Milvus 向量数据库
 */
@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private MemoryProperties memoryProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 插入一条新的记忆
     */
    public String insertMemory(String userId, String content, String type,
                                double confidence, String sourceSession) {
        String id = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        int ttlSeconds = getTtlSeconds(type);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", type);
        metadata.put("confidence", confidence);
        metadata.put("sourceSession", sourceSession);
        metadata.put("createdAt", now);
        metadata.put("updatedAt", now);
        metadata.put("lastAccessedAt", now);
        metadata.put("ttlSeconds", ttlSeconds);
        metadata.put("decayCount", 0);

        // 向量化
        List<Float> vector = embeddingService.generateEmbedding(content);

        try {
            String metadataJson = objectMapper.writeValueAsString(metadata);

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("user_id", Collections.singletonList(userId)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);
            if (response.getStatus() != 0) {
                logger.error("插入记忆失败: {}", response.getMessage());
                return null;
            }

            logger.info("记忆已插入: id={}, type={}, content（截断）={}", id, type,
                    content.length() > 50 ? content.substring(0, 50) + "..." : content);
            return id;
        } catch (Exception e) {
            logger.error("插入记忆异常", e);
            return null;
        }
    }

    /**
     * 向量搜索相似记忆（同一 userId）
     */
    public List<MemoryResult> searchSimilarMemories(String userId, String query, int topK) {
        try {
            List<Float> queryVector = embeddingService.generateQueryVector(query);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withMetricType(MetricType.L2)
                    .withTopK(topK)
                    .withExpr("user_id == \"" + userId + "\"")
                    .withOutFields(Arrays.asList("id", "content", "metadata"))
                    .withParams("{\"nprobe\": 10}")
                    .build();

            R<SearchResults> searchResponse = milvusClient.search(searchParam);
            if (searchResponse.getStatus() != 0) {
                logger.warn("搜索记忆失败: {}", searchResponse.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(
                    searchResponse.getData().getResults());
            List<MemoryResult> results = new ArrayList<>();

            List<?> idScoreList = wrapper.getIDScore(0);
            for (int i = 0; i < idScoreList.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> idScoreMap = (Map<String, Object>) idScoreList.get(i);

                MemoryResult result = new MemoryResult();
                result.setId((String) idScoreMap.get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));

                Object metaObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metaObj != null && metaObj instanceof String) {
                    Map<String, Object> meta = objectMapper.readValue((String) metaObj,
                            new TypeReference<Map<String, Object>>() {});
                    result.setType((String) meta.getOrDefault("type", "UNKNOWN"));
                    result.setConfidence(((Number) meta.getOrDefault("confidence", 0.0)).doubleValue());
                    result.setSourceSession((String) meta.getOrDefault("sourceSession", ""));
                }

                float score = ((Number) idScoreMap.get("score")).floatValue();
                result.setScore(1.0f - score);  // L2 距离 → 相似度
                results.add(result);
            }

            // 更新 lastAccessedAt
            for (MemoryResult r : results) {
                touchMemory(r.getId());
            }

            return results;
        } catch (Exception e) {
            logger.error("搜索记忆异常", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户的所有记忆（按类型分组，供前端面板使用）
     */
    public Map<String, List<MemoryResult>> getAllMemories(String userId) {
        try {
            milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .build()
            );

            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withExpr("user_id == \"" + userId + "\"")
                    .withOutFields(Arrays.asList("id", "content", "metadata"))
                    .withLimit(1000L)
                    .build();

            R<QueryResults> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0) {
                return Collections.emptyMap();
            }

            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            Map<String, List<MemoryResult>> grouped = new LinkedHashMap<>();
            grouped.put("facts", new ArrayList<>());
            grouped.put("profiles", new ArrayList<>());
            grouped.put("preferences", new ArrayList<>());

            for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                MemoryResult result = new MemoryResult();
                result.setId((String) record.get("id"));
                result.setContent((String) record.get("content"));

                Object metaObj = record.get("metadata");
                if (metaObj == null) continue;

                Map<String, Object> meta;
                if (metaObj instanceof String) {
                    meta = objectMapper.readValue((String) metaObj,
                            new TypeReference<Map<String, Object>>() {});
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) metaObj;
                    meta = m;
                }

                result.setType((String) meta.getOrDefault("type", "UNKNOWN"));
                result.setConfidence(((Number) meta.getOrDefault("confidence", 0.0)).doubleValue());
                result.setSourceSession((String) meta.getOrDefault("sourceSession", ""));
                result.setCreatedAt(((Number) meta.getOrDefault("createdAt", 0L)).longValue());
                result.setLastAccessedAt(((Number) meta.getOrDefault("lastAccessedAt", 0L)).longValue());
                result.setDecayCount(((Number) meta.getOrDefault("decayCount", 0)).intValue());

                switch (result.getType()) {
                    case "FACT":
                        grouped.get("facts").add(result); break;
                    case "PROFILE":
                        grouped.get("profiles").add(result); break;
                    case "PREFERENCE":
                        grouped.get("preferences").add(result); break;
                    default:
                        grouped.get("facts").add(result);
                }
            }

            return grouped;
        } catch (Exception e) {
            logger.error("获取所有记忆失败", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 查询用户特定类型的记忆（供 System Prompt 注入使用）
     */
    public List<MemoryResult> getMemoriesByTypes(String userId, List<String> types, int maxLength) {
        Map<String, List<MemoryResult>> all = getAllMemories(userId);
        List<MemoryResult> result = new ArrayList<>();

        for (String type : types) {
            List<MemoryResult> typeMemories;
            switch (type) {
                case "PROFILE":
                    typeMemories = all.get("profiles"); break;
                case "PREFERENCE":
                    typeMemories = all.get("preferences"); break;
                default:
                    typeMemories = all.get("facts"); break;
            }
            if (typeMemories != null) {
                result.addAll(typeMemories);
            }
        }

        // 按置信度降序
        result.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));

        // 截断到 maxLength
        int total = 0;
        List<MemoryResult> truncated = new ArrayList<>();
        for (MemoryResult r : result) {
            if (total + r.getContent().length() > maxLength) break;
            truncated.add(r);
            total += r.getContent().length();
        }
        return truncated;
    }

    /**
     * 删除单条记忆
     */
    public boolean deleteMemory(String memoryId) {
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withExpr("id == \"" + memoryId + "\"")
                .build();

        R<MutationResult> response = milvusClient.delete(deleteParam);
        if (response.getStatus() != 0) {
            logger.warn("删除记忆失败: {}", response.getMessage());
            return false;
        }
        logger.info("记忆已删除: id={}", memoryId);
        return true;
    }

    /**
     * 清空用户所有记忆
     */
    public long deleteAllMemories(String userId) {
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withExpr("user_id == \"" + userId + "\"")
                .build();

        R<MutationResult> response = milvusClient.delete(deleteParam);
        if (response.getStatus() != 0) {
            logger.warn("清空记忆失败: {}", response.getMessage());
            return 0;
        }
        long deleted = response.getData().getDeleteCnt();
        logger.info("已清空用户 {} 的全部记忆，删除 {} 条", userId, deleted);
        return deleted;
    }

    /**
     * 更新记忆内容和元数据（用于冲突处理 UPDATE/MERGE）
     */
    public boolean updateMemory(String memoryId, String newContent,
                                 Map<String, Object> newMetadata) {
        newMetadata.put("updatedAt", Instant.now().toEpochMilli());

        List<Float> vector = embeddingService.generateEmbedding(newContent);

        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(
                    "id", Collections.singletonList(memoryId)));
            fields.add(new InsertParam.Field(
                    "vector", Collections.singletonList(vector)));
            fields.add(new InsertParam.Field(
                    "content", Collections.singletonList(newContent)));
            fields.add(new InsertParam.Field(
                    "metadata", Collections.singletonList(objectMapper.writeValueAsString(newMetadata))));

            UpsertParam upsertParam = UpsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.upsert(upsertParam);
            if (response.getStatus() != 0) {
                logger.warn("更新记忆失败: {}", response.getMessage());
                return false;
            }
            logger.info("记忆已更新: id={}", memoryId);
            return true;
        } catch (Exception e) {
            logger.warn("更新记忆异常: id={}", memoryId, e);
            return false;
        }
    }

    /**
     * 更新记忆的 lastAccessedAt（touch）
     */
    private void touchMemory(String memoryId) {
        try {
            long now = Instant.now().toEpochMilli();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("lastAccessedAt", now);

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(
                    "id", Collections.singletonList(memoryId)));
            fields.add(new InsertParam.Field(
                    "metadata", Collections.singletonList(objectMapper.writeValueAsString(meta))));

            milvusClient.upsert(UpsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withFields(fields)
                    .build());
        } catch (Exception ignored) {
            // touch 失败不影响主流程
        }
    }

    private int getTtlSeconds(String type) {
        return switch (type) {
            case "FACT" -> memoryProperties.getTtl().getFactHours() * 3600;
            case "PROFILE" -> memoryProperties.getTtl().getProfileHours() * 3600;
            case "PREFERENCE" -> memoryProperties.getTtl().getPreferenceHours() * 3600;
            default -> 0;
        };
    }

    /**
     * 记忆搜索结果 DTO
     */
    public static class MemoryResult {
        private String id;
        private String type;
        private String content;
        private double confidence;
        private double score;
        private String sourceSession;
        private long createdAt;
        private long lastAccessedAt;
        private int decayCount;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getSourceSession() { return sourceSession; }
        public void setSourceSession(String sourceSession) { this.sourceSession = sourceSession; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getLastAccessedAt() { return lastAccessedAt; }
        public void setLastAccessedAt(long lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
        public int getDecayCount() { return decayCount; }
        public void setDecayCount(int decayCount) { this.decayCount = decayCount; }
    }
}
