package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import org.example.config.MemoryProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 记忆衰减服务
 * 每天定时扫描 user_memory collection，对长时间未访问的记忆进行置信度衰减
 */
@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryDecayService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryDecayService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private MemoryProperties memoryProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(cron = "${memory.decay.cron:0 3 * * *}")
    public void runDecayCycle() {
        if (!memoryProperties.getDecay().isEnabled()) {
            logger.debug("记忆衰减已禁用");
            return;
        }

        logger.info("开始记忆衰减周期");

        try {
            // 1. 确保 collection 已加载
            milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .build()
            );

            // 2. 查询所有记忆
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withOutFields(Arrays.asList("id", "user_id", "vector", "content", "metadata"))
                    .withLimit(1000L)  // 单次最多处理1000条
                    .build();

            R<QueryResults> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0) {
                logger.warn("查询记忆失败: {}", response.getMessage());
                return;
            }

            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();

            if (records.isEmpty()) {
                logger.info("无记忆需要处理");
                return;
            }

            int decayedCount = 0;
            int deletedCount = 0;
            long now = Instant.now().toEpochMilli();
            double decayFactor = memoryProperties.getDecay().getDecayFactor();
            double minConfidence = memoryProperties.getDecay().getMinConfidence();
            long noAccessThresholdMs = (long) memoryProperties.getDecay().getNoAccessThresholdHours() * 3600 * 1000;

            for (QueryResultsWrapper.RowRecord record : records) {
                String id = (String) record.get("id");
                Object metaObj = record.get("metadata");

                if (metaObj == null) continue;

                Map<String, Object> metadata;
                try {
                    if (metaObj instanceof String) {
                        metadata = objectMapper.readValue((String) metaObj,
                                new TypeReference<Map<String, Object>>() {});
                    } else if (metaObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) metaObj;
                        metadata = m;
                    } else {
                        continue;
                    }
                } catch (Exception e) {
                    logger.debug("解析记忆 metadata 失败: id={}", id, e);
                    continue;
                }

                // 检查上次访问时间
                Number lastAccessed = (Number) metadata.getOrDefault("lastAccessedAt", 0);
                if (now - lastAccessed.longValue() > noAccessThresholdMs) {
                    // 执行衰减
                    double confidence = ((Number) metadata.getOrDefault("confidence", 1.0)).doubleValue();
                    int decayCount = ((Number) metadata.getOrDefault("decayCount", 0)).intValue();

                    confidence = Math.max(0.0, confidence - decayFactor);
                    decayCount++;

                    if (confidence < minConfidence) {
                        // 删除
                        milvusClient.delete(DeleteParam.newBuilder()
                                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                                .withExpr("id == \"" + id + "\"")
                                .build());
                        deletedCount++;
                        logger.debug("删除低置信度记忆: id={}, confidence={}", id, confidence);
                    } else {
                        // 更新 — 需要完整字段才能 upsert
                        metadata.put("confidence", confidence);
                        metadata.put("decayCount", decayCount);

                        String userId = (String) record.get("user_id");
                        String content = (String) record.get("content");
                        @SuppressWarnings("unchecked")
                        List<Float> vector = (List<Float>) record.get("vector");

                        List<io.milvus.param.dml.InsertParam.Field> fields = new ArrayList<>();
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "id", Collections.singletonList(id)));
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "user_id", Collections.singletonList(userId != null ? userId : "")));
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "vector", Collections.singletonList(vector != null ? vector : Collections.emptyList())));
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "content", Collections.singletonList(content != null ? content : "")));
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "metadata", Collections.singletonList(objectMapper.writeValueAsString(metadata))));

                        milvusClient.upsert(UpsertParam.newBuilder()
                                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                                .withFields(fields)
                                .build());
                        decayedCount++;
                    }
                }
            }

            logger.info("记忆衰减完成: 衰减 {} 条, 删除 {} 条", decayedCount, deletedCount);

        } catch (Exception e) {
            logger.error("记忆衰减周期执行失败", e);
        }
    }
}
