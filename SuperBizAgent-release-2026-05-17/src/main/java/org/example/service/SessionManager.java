package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.SessionRedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器 — 基于 Redis 的三层会话存储
 *
 * Key 设计：
 * - session:{id}:summary — LLM 生成的对话摘要 JSON
 * - session:{id}:history  — 原始消息列表 JSON
 * - session:{id}:meta     — 元数据 JSON
 *
 * 读取路径：摘要优先 → 详情回退 → 新会话
 * 写入路径：追加详情 → 更新元数据 → 刷新 TTL → 超阈值异步生成摘要
 */
@Service
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    private static final String KEY_PREFIX = "session:";
    private static final String SUMMARY_SUFFIX = ":summary";
    private static final String HISTORY_SUFFIX = ":history";
    private static final String META_SUFFIX = ":meta";

    /** 最大历史消息窗口大小（成对计算） */
    private static final int MAX_WINDOW_SIZE = 6;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper redisObjectMapper;

    @Autowired
    private SessionRedisProperties props;

    @Autowired(required = false)
    private SummaryGenerator summaryGenerator;

    // ==================== 公共 API ====================

    /**
     * 获取或创建会话上下文
     * 读取路径：摘要层优先 → 详情层回退 → 新会话
     *
     * @param sessionId 会话ID（可为 null，null 时生成新 ID）
     * @return SessionContext 包含 sessionId 和历史消息
     */
    public SessionContext getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            logger.info("生成新会话ID: {}", sessionId);
            return new SessionContext(sessionId, Collections.emptyList(), null);
        }

        // 1. 如果启用了摘要，先查摘要层
        if (props.getSummary().isEnabled()) {
            String summaryJson = redisTemplate.opsForValue().get(summaryKey(sessionId));
            if (summaryJson != null && !summaryJson.isEmpty()) {
                try {
                    SummaryData summary = redisObjectMapper.readValue(summaryJson, SummaryData.class);
                    logger.info("命中摘要层 - SessionId: {}, 摘要长度: {}",
                            sessionId, summary.getSummary().length());

                    // 刷新所有 key 的 TTL
                    refreshTTL(sessionId);

                    // 返回摘要上下文（详情作为完整记录保留）
                    return new SessionContext(sessionId, Collections.emptyList(), summary.getSummary());
                } catch (JsonProcessingException e) {
                    logger.warn("摘要 JSON 解析失败，回退到详情层 - SessionId: {}", sessionId, e);
                }
            }
        }

        // 2. 摘要未命中或关闭，查详情层
        String historyJson = redisTemplate.opsForValue().get(historyKey(sessionId));
        if (historyJson != null && !historyJson.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> history = redisObjectMapper.readValue(historyJson, List.class);
                if (history != null && !history.isEmpty()) {
                    logger.info("命中详情层 - SessionId: {}, 消息对数: {}", sessionId, history.size() / 2);

                    // 刷新 TTL
                    refreshTTL(sessionId);

                    return new SessionContext(sessionId, history, null);
                }
            } catch (JsonProcessingException e) {
                logger.warn("详情 JSON 解析失败，视为新会话 - SessionId: {}", sessionId, e);
            }
        }

        // 3. 都未命中 → 新会话
        logger.info("新会话 - SessionId: {}", sessionId);
        return new SessionContext(sessionId, Collections.emptyList(), null);
    }

    /**
     * 添加一对消息（用户问题 + AI回复）
     *
     * @param sessionId   会话ID
     * @param userMessage 用户消息
     * @param aiMessage   AI 回复
     */
    @SuppressWarnings("unchecked")
    public void addMessage(String sessionId, String userMessage, String aiMessage) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        String historyKey = historyKey(sessionId);
        String metaKey = metaKey(sessionId);

        // 1. 从 Redis 读取现有历史
        String existingJson = redisTemplate.opsForValue().get(historyKey);
        List<Map<String, String>> history;
        try {
            if (existingJson != null && !existingJson.isEmpty()) {
                history = redisObjectMapper.readValue(existingJson, List.class);
            } else {
                history = new ArrayList<>();
            }
        } catch (JsonProcessingException e) {
            logger.warn("解析现有历史失败，重新开始 - SessionId: {}", sessionId, e);
            history = new ArrayList<>();
        }

        // 2. 追加用户消息和 AI 回复
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        history.add(userMsg);

        Map<String, String> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", aiMessage);
        history.add(assistantMsg);

        // 3. 滑动窗口裁剪（保持最多 MAX_WINDOW_SIZE 对）
        int maxMessages = MAX_WINDOW_SIZE * 2;
        while (history.size() > maxMessages) {
            history.remove(0); // 删除最旧的用户消息
            if (!history.isEmpty()) {
                history.remove(0); // 删除对应的 AI 回复
            }
        }

        // 4. 序列化并写回 Redis
        try {
            String historyJson = redisObjectMapper.writeValueAsString(history);
            writeWithTTL(historyKey, historyJson);
        } catch (JsonProcessingException e) {
            logger.error("序列化历史消息失败 - SessionId: {}", sessionId, e);
            return;
        }

        // 5. 更新元数据
        int messagePairCount = history.size() / 2;
        updateMeta(sessionId, messagePairCount);

        // 6. 重置 TTL
        refreshTTL(sessionId);

        logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", sessionId, messagePairCount);

        // 7. 检查是否需要触发摘要生成
        if (props.getSummary().isEnabled()
                && summaryGenerator != null
                && messagePairCount > props.getSummary().getTriggerThreshold()) {
            summaryGenerator.triggerAsync(sessionId);
        }
    }

    /**
     * 清空会话（删除所有三层 key）
     */
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        redisTemplate.delete(Arrays.asList(
                summaryKey(sessionId),
                historyKey(sessionId),
                metaKey(sessionId)
        ));

        logger.info("已清空会话 - SessionId: {}", sessionId);
    }

    /**
     * 检查会话是否存在
     */
    public boolean sessionExists(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        Boolean exists = redisTemplate.hasKey(historyKey(sessionId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 获取会话历史详情（仅详情层，不查摘要层）
     * 供前端展示会话历史使用
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getHistoryOnly(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Collections.emptyList();
        }
        String historyJson = redisTemplate.opsForValue().get(historyKey(sessionId));
        if (historyJson == null || historyJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return redisObjectMapper.readValue(historyJson, List.class);
        } catch (JsonProcessingException e) {
            logger.warn("解析历史消息失败 - SessionId: {}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取会话元数据
     */
    public SessionMeta getSessionMeta(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }

        String metaJson = redisTemplate.opsForValue().get(metaKey(sessionId));
        if (metaJson == null || metaJson.isEmpty()) {
            return null;
        }

        try {
            return redisObjectMapper.readValue(metaJson, SessionMeta.class);
        } catch (JsonProcessingException e) {
            logger.warn("解析元数据失败 - SessionId: {}", sessionId, e);
            return null;
        }
    }

    // ==================== 包内可见 API（供 SummaryGenerator 使用） ====================

    /**
     * 获取完整历史消息列表（供摘要生成使用）
     */
    @SuppressWarnings("unchecked")
    List<Map<String, String>> getFullHistory(String sessionId) {
        String historyJson = redisTemplate.opsForValue().get(historyKey(sessionId));
        if (historyJson == null || historyJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return redisObjectMapper.readValue(historyJson, List.class);
        } catch (JsonProcessingException e) {
            logger.warn("解析历史消息失败 - SessionId: {}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 写入摘要（供 SummaryGenerator 使用）
     */
    void saveSummary(String sessionId, SummaryData summary) {
        try {
            String json = redisObjectMapper.writeValueAsString(summary);
            writeWithTTL(summaryKey(sessionId), json);
            logger.info("摘要已写入 - SessionId: {}, 长度: {}",
                    sessionId, summary.getSummary().length());
        } catch (JsonProcessingException e) {
            logger.error("序列化摘要失败 - SessionId: {}", sessionId, e);
        }
    }

    /**
     * 尝试获取摘要生成分布式锁（SETNX）
     * @return true 表示获取锁成功
     */
    boolean tryAcquireSummaryLock(String sessionId) {
        String lockKey = summaryLockKey(sessionId);
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(60));
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放摘要生成分布式锁
     */
    void releaseSummaryLock(String sessionId) {
        redisTemplate.delete(summaryLockKey(sessionId));
    }

    // ==================== 内部方法 ====================

    private void updateMeta(String sessionId, int messagePairCount) {
        SessionMeta meta = getSessionMeta(sessionId);
        if (meta == null) {
            meta = new SessionMeta();
            meta.setCreateTime(System.currentTimeMillis());
        }
        meta.setMessagePairCount(messagePairCount);
        meta.setLastAccessTime(System.currentTimeMillis());

        try {
            String metaJson = redisObjectMapper.writeValueAsString(meta);
            writeWithTTL(metaKey(sessionId), metaJson);
        } catch (JsonProcessingException e) {
            logger.warn("序列化元数据失败 - SessionId: {}", sessionId, e);
        }
    }

    private void refreshTTL(String sessionId) {
        Duration ttl = getTTLDuration();
        if (ttl == null) {
            return; // TTL=0，不设过期
        }
        redisTemplate.expire(summaryKey(sessionId), ttl);
        redisTemplate.expire(historyKey(sessionId), ttl);
        redisTemplate.expire(metaKey(sessionId), ttl);
    }

    private void writeWithTTL(String key, String value) {
        Duration ttl = getTTLDuration();
        if (ttl != null) {
            redisTemplate.opsForValue().set(key, value, ttl);
        } else {
            redisTemplate.opsForValue().set(key, value);
        }
    }

    private Duration getTTLDuration() {
        if (props.getTtlHours() <= 0) {
            return null; // 永不过期
        }
        return Duration.ofHours(props.getTtlHours());
    }

    // ==================== Key 工具方法 ====================

    private String summaryKey(String sessionId) {
        return KEY_PREFIX + sessionId + SUMMARY_SUFFIX;
    }

    private String historyKey(String sessionId) {
        return KEY_PREFIX + sessionId + HISTORY_SUFFIX;
    }

    private String metaKey(String sessionId) {
        return KEY_PREFIX + sessionId + META_SUFFIX;
    }

    private String summaryLockKey(String sessionId) {
        return KEY_PREFIX + sessionId + ":summary-lock";
    }

    // ==================== 内部数据类 ====================

    /**
     * 会话上下文（查询结果）
     */
    public static class SessionContext {
        private final String sessionId;
        private final List<Map<String, String>> history;
        private final String summary;

        public SessionContext(String sessionId, List<Map<String, String>> history, String summary) {
            this.sessionId = sessionId;
            this.history = history != null ? history : Collections.emptyList();
            this.summary = summary;
        }

        public String getSessionId() { return sessionId; }
        public List<Map<String, String>> getHistory() { return history; }
        public String getSummary() { return summary; }
        public boolean hasSummary() { return summary != null && !summary.isEmpty(); }
    }

    /**
     * 摘要数据
     */
    public static class SummaryData {
        private String summary;
        private long summaryTime;
        private int summarizedMessageCount;

        public SummaryData() {}

        public SummaryData(String summary, long summaryTime, int summarizedMessageCount) {
            this.summary = summary;
            this.summaryTime = summaryTime;
            this.summarizedMessageCount = summarizedMessageCount;
        }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public long getSummaryTime() { return summaryTime; }
        public void setSummaryTime(long summaryTime) { this.summaryTime = summaryTime; }
        public int getSummarizedMessageCount() { return summarizedMessageCount; }
        public void setSummarizedMessageCount(int summarizedMessageCount) { this.summarizedMessageCount = summarizedMessageCount; }
    }

    /**
     * 会话元数据
     */
    public static class SessionMeta {
        private long createTime;
        private int messagePairCount;
        private long lastAccessTime;
        private long lastSummaryTime;

        public long getCreateTime() { return createTime; }
        public void setCreateTime(long createTime) { this.createTime = createTime; }
        public int getMessagePairCount() { return messagePairCount; }
        public void setMessagePairCount(int messagePairCount) { this.messagePairCount = messagePairCount; }
        public long getLastAccessTime() { return lastAccessTime; }
        public void setLastAccessTime(long lastAccessTime) { this.lastAccessTime = lastAccessTime; }
        public long getLastSummaryTime() { return lastSummaryTime; }
        public void setLastSummaryTime(long lastSummaryTime) { this.lastSummaryTime = lastSummaryTime; }
    }
}
