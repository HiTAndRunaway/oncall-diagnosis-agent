package org.example.service;

import org.example.config.ChatModelFactory;
import org.example.config.ModelProperties;
import org.example.config.SessionRedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 异步摘要生成器
 *
 * 当会话历史消息对数超过阈值时，异步调用 LLM 生成对话摘要。
 * 使用 Redis SETNX 分布式锁防止并发重复生成。
 * 详情层始终保留，不会被删除。
 */
@Service
public class SummaryGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SummaryGenerator.class);

    /**
     * @Lazy 打破与 SessionManager 的循环依赖（SessionManager 也注入本类）：
     * Boot 默认禁止循环引用，本字段仅在异步 triggerAsync 运行时使用，代理注入安全。
     */
    @Autowired
    @Lazy
    private SessionManager sessionManager;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SessionRedisProperties props;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Autowired
    private ModelProperties modelProperties;

    /**
     * 异步触发摘要生成
     * 由 SessionManager.addMessage() 在消息对数超过阈值时调用
     */
    @Async("summaryExecutor")
    public void triggerAsync(String sessionId) {
        logger.info("异步摘要生成触发 - SessionId: {}", sessionId);

        // 1. 尝试获取分布式锁
        if (!sessionManager.tryAcquireSummaryLock(sessionId)) {
            logger.info("摘要已在生成中（锁已被持有），跳过 - SessionId: {}", sessionId);
            return;
        }

        try {
            // 2. 获取完整历史
            List<Map<String, String>> history = sessionManager.getFullHistory(sessionId);
            if (history.isEmpty()) {
                logger.warn("历史消息为空，跳过摘要生成 - SessionId: {}", sessionId);
                return;
            }

            logger.info("开始生成摘要 - SessionId: {}, 消息条数: {}", sessionId, history.size());

            // 3. 构建摘要提示词
            int maxLen = props.getSummary().getMaxSummaryLength();
            String historyText = buildHistoryText(history);
            String summaryPrompt = buildSummaryPrompt(historyText, maxLen);

            // 4. 调用 LLM 生成摘要
            String summary = generateSummary(summaryPrompt);

            if (summary == null || summary.isEmpty()) {
                logger.warn("LLM 返回空摘要 - SessionId: {}", sessionId);
                return;
            }

            // 截断到最大长度
            if (summary.length() > maxLen) {
                summary = summary.substring(0, maxLen);
            }

            // 5. 保存摘要（覆盖旧摘要，详情层保留）
            SessionManager.SummaryData summaryData = new SessionManager.SummaryData(
                    summary,
                    System.currentTimeMillis(),
                    history.size() / 2
            );
            sessionManager.saveSummary(sessionId, summaryData);

            // 6. 更新元数据中的摘要时间戳
            SessionManager.SessionMeta meta = sessionManager.getSessionMeta(sessionId);
            if (meta != null) {
                meta.setLastSummaryTime(System.currentTimeMillis());
            }

            logger.info("摘要生成完成 - SessionId: {}, 摘要长度: {}", sessionId, summary.length());

        } catch (Exception e) {
            logger.error("摘要生成失败 - SessionId: {}", sessionId, e);
        } finally {
            // 7. 释放锁
            sessionManager.releaseSummaryLock(sessionId);
        }
    }

    /**
     * 调用 LLM 生成摘要（经 ChatModelFactory 构建，支持 liteLLM 网关切换）
     */
    private String generateSummary(String prompt) {
        try {
            ModelProperties.ModelConfig cfg = modelProperties.getLightweight();
            ChatModel chatModel = chatModelFactory.create(cfg);

            // 使用 call 方法进行非流式调用
            String response = chatModel.call(prompt);
            if (response != null && !response.isEmpty()) {
                return response;
            }

        } catch (Exception e) {
            logger.error("LLM 摘要生成调用失败", e);
        }
        return null;
    }

    /**
     * 构建历史消息文本
     */
    private String buildHistoryText(List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                sb.append("用户: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("助手: ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 构建摘要生成的系统提示词
     */
    private String buildSummaryPrompt(String historyText, int maxLen) {
        return String.format(
                "请将以下对话历史压缩为一段不超过%d字的摘要。\n" +
                "摘要应包含：关键主题、重要信息、用户需求和已得到的结论。\n" +
                "只输出摘要文本，不要包含任何前缀或说明。\n\n" +
                "--- 对话历史 ---\n%s\n--- 对话历史结束 ---\n\n" +
                "请输出摘要：",
                maxLen, historyText
        );
    }
}
