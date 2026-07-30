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

    /**
     * 构建系统提示词（包含历史消息或摘要）
     * @param history 历史消息列表（摘要模式下为 emptyList）
     * @param summary 对话摘要（可为 null）
     * @param userId  当前用户 ID
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history, String summary, String userId) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n");

        // Agentic RAG 模式：注入细粒度检索工具使用指南
        if (agenticRagEnabled) {
            systemPromptBuilder.append(buildAgenticRagInstructions());
        }

        systemPromptBuilder.append("\n");

        // 记忆注入：用户画像 + 偏好
        if (memoryEnabled && memoryManager != null && memoryProperties != null) {
            String memoryBlock = buildMemoryProfileBlock(userId);
            if (!memoryBlock.isEmpty()) {
                systemPromptBuilder.append(memoryBlock);
            }
        }

        // 摘要模式：优先使用摘要
        if (summary != null && !summary.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史摘要 ---\n");
            systemPromptBuilder.append("以下是此前对话的摘要：\n");
            systemPromptBuilder.append(summary).append("\n");
            systemPromptBuilder.append("--- 对话历史摘要结束 ---\n\n");
            systemPromptBuilder.append("请基于以上对话历史摘要，回答用户的新问题。");
            return systemPromptBuilder.toString();
        }

        // 详情模式：拼接原始历史消息
        if (history != null && !history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }

    /**
     * 构建系统提示词（不含摘要，向后兼容）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildSystemPrompt(history, null, null);
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
     * 构建 Agentic RAG 系统提示词指令块
     * 仅在 rag.agentic.enabled=true 时追加到系统提示词中
     */
    private String buildAgenticRagInstructions() {
        return String.format("""

                ## 知识检索策略（Agentic RAG）

                你有多个知识检索工具，请按以下策略使用：

                ### 检索流程
                1. **了解能力**：首次处理用户问题时，调用 getSearchCapabilities 了解可用检索能力
                2. **判断问题类型**：
                   - 简单事实类 → 直接调用 queryInternalDocs 或 searchKnowledgeBase
                   - 对比/分析/多步类 → 先调用 decomposeQuestion 拆解子问题
                   - 纯闲聊/无事实需求 → 直接回答，无需检索
                3. **执行检索**：对每个(子)问题调用 searchKnowledgeBase，topK 默认 5
                4. **评估质量**：每次检索后调用 evaluateSearchResults 判断相关性
                5. **精炼重试**：当 recommendation 为 REFINE 时，调用 refineQuery 改写后重新检索

                ### 停止条件（满足任一即停止检索，基于已有结果生成答案）
                - 有 ≥1 条结果相关性 ≥ %.1f
                - _meta.remainingRounds == 0
                - 同一 query 连续 2 次评估 recommendation 仍为 REFINE

                ### 生成阶段
                - 综合所有达标结果生成答案，注明信息来源
                - 如果确实无相关信息，如实告知用户，不要编造
                - 严禁无限检索！remainingRounds 为 0 时必须基于已有最好结果强制回答
                """, agenticMinRelevanceScore);
    }
}
