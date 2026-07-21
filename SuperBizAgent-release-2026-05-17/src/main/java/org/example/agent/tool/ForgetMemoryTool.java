package org.example.agent.tool;

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

    @Tool(description = """
            删除用户的记忆。当用户明确要求"忘记"某些信息时调用。\
            先按关键词搜索记忆，确认匹配后删除。返回删除结果。""")
    public String forgetMemory(
            @ToolParam(description = "要删除的记忆关键词，用于搜索匹配的记忆") String target,
            @ToolParam(description = "当前用户ID") String userId) {

        logger.info("Agent 调用 forgetMemory: userId={}, target={}", userId, target);

        if (userId == null || userId.isEmpty()) {
            return "{\"success\": false, \"message\": \"未设置用户ID\"}";
        }

        // 1. 先搜索匹配的记忆
        List<MemoryManager.MemoryResult> matches =
                memoryManager.searchSimilarMemories(userId, target, 3);

        if (matches.isEmpty()) {
            return "{\"success\": false, \"message\": \"未找到匹配的记忆\", \"deletedCount\": 0}";
        }

        // 2. 删除匹配的记忆
        int deleted = 0;
        for (MemoryManager.MemoryResult match : matches) {
            if (match.getScore() > 0.5) {  // 相似度阈值
                if (memoryManager.deleteMemory(match.getId())) {
                    deleted++;
                }
            }
        }

        return String.format(
            "{\"success\": true, \"message\": \"已删除 %d 条记忆\", \"deletedCount\": %d}",
            deleted, deleted);
    }
}
