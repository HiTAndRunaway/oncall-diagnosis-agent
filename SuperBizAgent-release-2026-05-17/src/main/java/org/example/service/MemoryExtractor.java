package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆提取器
 * 异步从对话历史中提取用户事实、画像和偏好，存入长期记忆库
 */
@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractor.class);

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MemoryProperties memoryProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DashScopeLlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MEMORY_LOCK_SUFFIX = ":memory-lock";

    /**
     * 异步触发记忆提取
     * 由 SessionManager.addMessage() 检测增量达标后调用
     */
    @Async("memoryExecutor")
    public void extractAsync(String sessionId, String userId) {
        if (userId == null || userId.isEmpty()) {
            logger.debug("无 userId，跳过记忆提取 - sessionId={}", sessionId);
            return;
        }

        // 分布式锁防重
        String lockKey = "session:" + sessionId + MEMORY_LOCK_SUFFIX;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(locked)) {
            logger.debug("记忆提取锁已被持有，跳过 - sessionId={}", sessionId);
            return;
        }

        try {
            logger.info("开始异步记忆提取 - sessionId={}, userId={}", sessionId, userId);
            doExtract(sessionId, userId);
        } catch (Exception e) {
            logger.error("记忆提取失败 - sessionId={}", sessionId, e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void doExtract(String sessionId, String userId) {
        // 1. 读取对话历史
        List<Map<String, String>> history = sessionManager.getFullHistory(sessionId);
        if (history.isEmpty()) {
            logger.debug("无对话历史，跳过提取");
            return;
        }

        // 限制最大分析条数
        int maxBatch = memoryProperties.getExtraction().getMaxBatchMessages();
        if (history.size() > maxBatch) {
            history = history.subList(history.size() - maxBatch, history.size());
        }

        // 2. 读取已有记忆
        List<MemoryManager.MemoryResult> existingMemories =
                memoryManager.getMemoriesByTypes(userId,
                        Arrays.asList("FACT", "PROFILE", "PREFERENCE"), 2000);
        String existingSummary = existingMemories.stream()
                .map(m -> String.format("[%s] %s (置信度:%.0f%%)",
                        m.getType(), m.getContent(), m.getConfidence() * 100))
                .collect(Collectors.joining("\n"));

        // 3. 构建提取 prompt
        String conversationText = history.stream()
                .map(m -> ("user".equals(m.get("role")) ? "用户: " : "助手: ") + m.get("content"))
                .collect(Collectors.joining("\n"));

        String extractionPrompt = buildExtractionPrompt(existingSummary, conversationText);

        // 4. 调用轻量 LLM 提取
        String model = memoryProperties.getExtraction().getModel();
        String llmResponse = callLlm(model, "你是一个记忆提取器。", extractionPrompt);

        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            logger.debug("LLM 返回空，无法提取记忆");
            return;
        }

        // 5. 解析提取结果
        String jsonBlock = extractJson(llmResponse);
        if (jsonBlock == null) {
            logger.debug("未能从 LLM 响应中解析 JSON: {}", llmResponse.substring(0, Math.min(100, llmResponse.length())));
            return;
        }

        Map<String, Object> result;
        try {
            result = objectMapper.readValue(jsonBlock, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("JSON 解析失败: {}", e.getMessage());
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> memories = (List<Map<String, Object>>) result.get("memories");
        if (memories == null || memories.isEmpty()) {
            logger.info("本轮未提取到新记忆");
            return;
        }

        // 6. 逐条处理：冲突检测 → 写入
        int newCount = 0, updateCount = 0, mergeCount = 0;
        for (Map<String, Object> memory : memories) {
            String type = (String) memory.get("type");
            String content = (String) memory.get("content");
            double confidence = ((Number) memory.getOrDefault("confidence", 0.5)).doubleValue();

            if (type == null || content == null || content.trim().isEmpty()) continue;
            if (confidence < 0.5) continue;  // 过滤低置信度

            // 搜索冲突
            List<MemoryManager.MemoryResult> conflicts =
                    memoryManager.searchSimilarMemories(userId, content, 1);

            if (conflicts.isEmpty() || conflicts.get(0).getScore() < memoryProperties.getSearch().getScoreThreshold()) {
                // NEW
                memoryManager.insertMemory(userId, content, type, confidence, sessionId);
                newCount++;
            } else {
                // 有冲突 → LLM 判断
                MemoryManager.MemoryResult conflict = conflicts.get(0);
                String action = resolveConflict(conflict.getContent(), conflict.getConfidence(),
                        content, confidence);

                switch (action) {
                    case "UPDATE":
                        Map<String, Object> newMeta = new LinkedHashMap<>();
                        newMeta.put("type", type);
                        newMeta.put("confidence", Math.max(confidence, conflict.getConfidence()));
                        newMeta.put("sourceSession", sessionId);
                        newMeta.put("decayCount", 0);
                        memoryManager.updateMemory(conflict.getId(), content, newMeta);
                        updateCount++;
                        break;
                    case "MERGE":
                        String mergedContent = resolveMerge(conflict.getContent(), content);
                        Map<String, Object> mergeMeta = new LinkedHashMap<>();
                        mergeMeta.put("type", type);
                        mergeMeta.put("confidence", Math.max(confidence, conflict.getConfidence()));
                        mergeMeta.put("sourceSession", sessionId);
                        mergeMeta.put("decayCount", 0);
                        memoryManager.updateMemory(conflict.getId(), mergedContent, mergeMeta);
                        mergeCount++;
                        break;
                    default: // NEW
                        memoryManager.insertMemory(userId, content, type, confidence, sessionId);
                        newCount++;
                }
            }
        }

        // 7. 更新 session meta
        SessionManager.SessionMeta meta = sessionManager.getSessionMeta(sessionId);
        if (meta != null) {
            meta.setLastExtractedMessageCount(meta.getMessagePairCount());
            sessionManager.updateSessionMeta(sessionId, meta);
        }

        logger.info("记忆提取完成: 新增{}条, 更新{}条, 合并{}条", newCount, updateCount, mergeCount);
    }

    private String buildExtractionPrompt(String existingMemories, String conversation) {
        return String.format("""
            分析以下对话，提取关于用户的重要信息。

            已有记忆：
            %s

            对话历史：
            %s

            请提取三类信息：
            1. FACT（事实结论）：用户明确提到的技术事实、环境信息、历史决策结果
            2. PROFILE（用户画像）：用户的职业角色、技能领域、职责范围
            3. PREFERENCE（行为偏好）：用户表达的信息呈现偏好、工作习惯、交流风格

            要求：
            - 只提取明确的信息，不要推测
            - 每条记忆置信度 0-1，模糊信息给低分
            - 如果对话中没有值得提取的信息，返回空列表
            - 输出 JSON: {"memories": [{"type": "FACT", "content": "...", "confidence": 0.9}]}
            """, existingMemories.isEmpty() ? "（无已有记忆）" : existingMemories, conversation);
    }

    private String resolveConflict(String oldContent, double oldConf,
                                    String newContent, double newConf) {
        String prompt = String.format("""
            用户已有以下记忆：
            旧记忆: "%s" (置信度: %.0f%%)

            从最新对话中提取到：
            新记忆: "%s" (置信度: %.0f%%)

            判断新旧记忆的关系：
            - UPDATE: 新信息是旧信息的更新（如版本升级），覆盖旧记忆
            - MERGE: 两者可以合并为一条更完整的记忆
            - NEW: 两者是不同的信息，应该各自保留

            输出 JSON: {"action": "UPDATE|MERGE|NEW", "reason": "..."}
            """, oldContent, oldConf * 100, newContent, newConf * 100);

        String response = callLlm("qwen-turbo", "你是一个记忆冲突判断器。", prompt);
        if (response == null) return "NEW"; // 超时默认 NEW

        String json = extractJson(response);
        if (json == null) return "NEW";

        try {
            Map<String, Object> result = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return (String) result.getOrDefault("action", "NEW");
        } catch (Exception e) {
            return "NEW";
        }
    }

    private String resolveMerge(String oldContent, String newContent) {
        String prompt = String.format("""
            以下两条记忆描述的是同一信息，请将它们合并为一条简洁完整的记忆：

            记忆1: "%s"
            记忆2: "%s"

            输出合并后的记忆文本（仅输出文本，不要 JSON）。
            """, oldContent, newContent);

        String response = callLlm("qwen-turbo", "你是一个信息整合器。", prompt);
        return response != null ? response.trim() : (oldContent + "；" + newContent);
    }

    private String callLlm(String model, String systemPrompt, String userMessage) {
        try {
            return llmClient.callWithSystemPrompt(model, systemPrompt, userMessage, 0.3, 2000);
        } catch (Exception e) {
            logger.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }
}
