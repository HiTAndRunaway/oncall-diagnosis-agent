package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 记忆搜索服务
 * 封装向量搜索逻辑，为 RecallMemoryTool 提供 JSON 格式结果
 */
@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemorySearchService {

    private static final Logger logger = LoggerFactory.getLogger(MemorySearchService.class);

    @Autowired
    private MemoryManager memoryManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 搜索用户记忆（供 RecallMemoryTool 调用）
     * @return JSON 格式的搜索结果
     */
    public String search(String userId, String query, int topK) {
        if (userId == null || userId.isEmpty()) {
            return "{\"error\": \"userId is required\", \"results\": []}";
        }

        logger.info("搜索记忆: userId={}, query={}, topK={}", userId, query, topK);

        List<MemoryManager.MemoryResult> results =
                memoryManager.searchSimilarMemories(userId, query, topK);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("totalResults", results.size());

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (MemoryManager.MemoryResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("type", r.getType());
            item.put("content", r.getContent());
            item.put("confidence", r.getConfidence());
            item.put("score", Math.round(r.getScore() * 100.0) / 100.0);
            resultList.add(item);
        }
        response.put("results", resultList);

        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            logger.warn("序列化搜索结果失败", e);
            return "{\"error\": \"serialization failed\", \"results\": []}";
        }
    }
}
