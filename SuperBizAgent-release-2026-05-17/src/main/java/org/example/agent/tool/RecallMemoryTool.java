package org.example.agent.tool;

import org.example.security.CurrentUser;
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
 * <p>
 * 用户身份在<b>方法执行时</b>从 Spring Security 上下文读取（{@link CurrentUser}），
 * 而非由上游手工塞入静态状态。这样并发请求、流式线程切换、异步执行下都不会串号。
 */
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class RecallMemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(RecallMemoryTool.class);

    @Autowired
    private MemorySearchService memorySearchService;

    @Tool(description = """
            查询用户的历史记忆。当需要回忆用户之前提到过的技术细节、\
            历史决策、具体偏好时调用此工具。返回匹配的记忆内容和置信度。""")
    public String recallMemory(
            @ToolParam(description = "搜索查询文本，用自然语言描述要查找的记忆内容") String query,
            @ToolParam(description = "返回数量，默认3，最大10") Integer topK) {

        String userId = CurrentUser.getId();
        if (!CurrentUser.isAuthenticated()) {
            logger.warn("recallMemory 调用被拒绝：当前无已认证用户身份，query={}", query);
            return "{\"error\": \"未认证，无法查询记忆\", \"results\": []}";
        }

        int k = topK != null ? Math.min(topK, 10) : 3;
        logger.info("Agent 调用 recallMemory: userId={}, query={}, topK={}", userId, query, k);

        return memorySearchService.search(userId, query, k);
    }
}
