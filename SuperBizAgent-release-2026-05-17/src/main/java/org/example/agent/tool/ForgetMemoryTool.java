package org.example.agent.tool;

import org.example.config.MemoryProperties;
import org.example.security.CurrentUser;
import org.example.service.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆删除工具
 * 供 Agent 根据用户指令删除记忆
 */
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class ForgetMemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(ForgetMemoryTool.class);

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private MemoryProperties memoryProperties;

    @Tool(description = """
            删除当前用户自己的记忆。当用户明确要求"忘记"某些信息时调用。\
            先按关键词搜索记忆，确认匹配后删除。返回删除结果。\
            只能操作当前登录用户自己的记忆，无需也不允许指定用户。""")
    public String forgetMemory(
            @ToolParam(description = "要删除的记忆关键词，用于搜索匹配的记忆") String target) {

        // 身份从认证上下文读取，不接受模型/入参指定，避免越权删除他人记忆
        String userId = resolveUserId();
        if (userId == null) {
            logger.warn("forgetMemory 调用被拒绝：当前无已认证用户身份，target={}", target);
            return "{\"success\": false, \"message\": \"未认证，无法删除记忆\"}";
        }

        logger.info("Agent 调用 forgetMemory: userId={}, target={}", userId, target);

        // 1. 先搜索匹配的记忆
        List<MemoryManager.MemoryResult> matches =
                memoryManager.searchSimilarMemories(userId, target, 3);

        if (matches.isEmpty()) {
            return "{\"success\": false, \"message\": \"未找到匹配的记忆\", \"deletedCount\": 0}";
        }

        // 2. 删除匹配的记忆
        int deleted = 0;
        for (MemoryManager.MemoryResult match : matches) {
            if (match.getScore() > memoryProperties.getSearch().getScoreThreshold()) {
                if (memoryManager.deleteMemory(userId, match.getId())) {
                    deleted++;
                }
            }
        }

        return String.format(
            "{\"success\": true, \"message\": \"已删除 %d 条记忆\", \"deletedCount\": %d}",
            deleted, deleted);
    }

    /**
     * 解析本次调用应使用的用户身份
     *
     * @return 已认证用户 ID；未认证且 {@code memory.require-authenticated=true} 时返回
     *         null（由调用方拒绝），否则返回 {@link CurrentUser#ANONYMOUS}（改造前的兼容行为）
     */
    private String resolveUserId() {
        if (CurrentUser.isAuthenticated()) {
            return CurrentUser.getId();
        }
        if (memoryProperties != null && !memoryProperties.isRequireAuthenticated()) {
            logger.warn("memory.require-authenticated=false：forgetMemory 以匿名桶执行，"
                    + "多用户环境会造成记忆串扰，请勿在生产开启");
            return CurrentUser.ANONYMOUS;
        }
        return null;
    }
}
