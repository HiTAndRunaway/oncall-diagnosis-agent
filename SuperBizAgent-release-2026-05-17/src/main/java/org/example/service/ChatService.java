package org.example.service;

import org.example.agent.tool.ForgetMemoryTool;
import org.example.agent.tool.RecallMemoryTool;
import org.example.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务 — 纯业务逻辑层
 * 负责系统提示词构建与记忆注入，不涉及 Agent 创建/执行/模型实例化。
 * Agent 创建与执行已迁移至 {@link org.example.agent.AgentRunner} 实现。
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Value("${rag.agentic.enabled:false}")
    private boolean agenticRagEnabled;

    @Value("${rag.agentic.min-relevance-score:0.6}")
    private double agenticMinRelevanceScore;

    // ===== 记忆注入 =====
    @Autowired(required = false)
    private MemoryManager memoryManager;

    @Autowired(required = false)
    private MemoryProperties memoryProperties;

    @Value("${memory.enabled:false}")
    private boolean memoryEnabled;

    @Autowired(required = false)
    private RecallMemoryTool recallMemoryTool;

    @Autowired(required = false)
    private ForgetMemoryTool forgetMemoryTool;

    // ===== Prompt 管理 =====
    @Autowired
    private PromptManager promptManager;

    /**
     * 构建系统提示词（完整参数，支持多语言）
     * @param history 历史消息列表（摘要模式下为 emptyList）
     * @param summary 对话摘要（可为 null）
     * @param userId  当前用户 ID
     * @param lang    语言代码（"zh"/"en"），null 则使用默认语言
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history, String summary, String userId, String lang) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("agenticRagEnabled", agenticRagEnabled);
        vars.put("minRelevanceScore", String.format("%.1f", agenticMinRelevanceScore));

        // 记忆注入：用户画像 + 偏好
        String memoryBlock = "";
        if (memoryEnabled && memoryManager != null && memoryProperties != null && userId != null && !userId.isEmpty()) {
            memoryBlock = buildMemoryProfileBlock(userId);
        }
        vars.put("memoryProfileBlock", memoryBlock);

        // 摘要模式 vs 详情模式
        if (summary != null && !summary.isEmpty()) {
            vars.put("summaryBlock", summary);
            vars.put("historyBlock", "");
        } else if (history != null && !history.isEmpty()) {
            vars.put("summaryBlock", "");
            vars.put("historyBlock", buildHistoryText(history));
        } else {
            vars.put("summaryBlock", "");
            vars.put("historyBlock", "");
        }

        return promptManager.render("chat/system-prompt", vars, lang);
    }

    /**
     * 构建系统提示词（包含历史消息或摘要，默认中文）
     * @param history 历史消息列表（摘要模式下为 emptyList）
     * @param summary 对话摘要（可为 null）
     * @param userId  当前用户 ID
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history, String summary, String userId) {
        return buildSystemPrompt(history, summary, userId, "zh");
    }

    /**
     * 构建系统提示词（不含摘要，向后兼容）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildSystemPrompt(history, null, null, "zh");
    }

    /**
     * 构建用户画像记忆区块（供 System Prompt 注入）
     */
    private String buildMemoryProfileBlock(String userId) {
        if (userId == null || userId.isEmpty()) return "";

        List<String> types = new ArrayList<>();
        if (memoryProperties.getSystemPrompt().isInjectProfile()) types.add("PROFILE");
        if (memoryProperties.getSystemPrompt().isInjectPreferences()) types.add("PREFERENCE");
        if (types.isEmpty()) return "";

        List<MemoryManager.MemoryResult> memories = memoryManager.getMemoriesByTypes(
                userId, types,
                memoryProperties.getSystemPrompt().getMaxLength()
        );

        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n## 用户画像\n\n");
        sb.append("关于用户你知道：\n");
        for (MemoryManager.MemoryResult m : memories) {
            sb.append("- ").append(m.getContent()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 将历史消息列表转换为文本块
     * @param history 历史消息列表
     * @return 格式化后的历史对话文本
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
}
