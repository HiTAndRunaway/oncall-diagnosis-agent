package org.example.agent.tool;

import org.example.service.MemorySearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 记忆召回工具
 * 供 Agent 按需查询用户历史记忆
 */
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class RecallMemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(RecallMemoryTool.class);

    @Autowired
    private MemorySearchService memorySearchService;

    /**
     * 通过 ThreadLocal 从 ChatController 传递当前 userId
     */
    private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    public static void setCurrentUserId(String userId) {
        currentUserId.set(userId);
    }

    public static void clearCurrentUserId() {
        currentUserId.remove();
    }

    @Tool(description = """
            查询用户的历史记忆。当需要回忆用户之前提到过的技术细节、\
            历史决策、具体偏好时调用此工具。返回匹配的记忆内容和置信度。""")
    public String recallMemory(
            @ToolParam(description = "搜索查询文本，用自然语言描述要查找的记忆内容") String query,
            @ToolParam(description = "返回数量，默认3，最大10") Integer topK) {

        String userId = currentUserId.get();
        if (userId == null || userId.isEmpty()) {
            return "{\"error\": \"未设置用户ID，无法查询记忆\", \"results\": []}";
        }

        int k = topK != null ? Math.min(topK, 10) : 3;
        logger.info("Agent 调用 recallMemory: userId={}, query={}, topK={}", userId, query, k);

        return memorySearchService.search(userId, query, k);
    }
}
