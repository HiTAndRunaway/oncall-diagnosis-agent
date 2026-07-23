package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.DecomposeQuestionTool;
import org.example.agent.tool.EvaluateSearchResultsTool;
import org.example.agent.tool.ForgetMemoryTool;
import org.example.agent.tool.GetSearchCapabilitiesTool;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.RecallMemoryTool;
import org.example.agent.tool.RefineQueryTool;
import org.example.agent.tool.SearchKnowledgeBaseTool;
import org.example.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    // ===== Agentic RAG 工具（仅在 rag.agentic.enabled=true 时注册为 Bean） =====
    @Autowired(required = false)
    private SearchKnowledgeBaseTool searchKnowledgeBaseTool;

    @Autowired(required = false)
    private EvaluateSearchResultsTool evaluateSearchResultsTool;

    @Autowired(required = false)
    private RefineQueryTool refineQueryTool;

    @Autowired(required = false)
    private DecomposeQuestionTool decomposeQuestionTool;

    @Autowired(required = false)
    private GetSearchCapabilitiesTool getSearchCapabilitiesTool;

    @Autowired
    private AgenticRagGuard agenticRagGuard;

    @Autowired
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

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
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息或摘要）
     * @param history 历史消息列表（摘要模式下为 emptyList）
     * @param summary 对话摘要（可为 null）
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
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     * 根据 rag.agentic.enabled 决定是否包含 Agentic RAG 工具
     */
    public Object[] buildMethodToolsArray() {
        List<Object> toolList = new ArrayList<>();
        toolList.add(dateTimeTools);
        toolList.add(internalDocsTools);
        toolList.add(queryMetricsTools);

        if (queryLogsTools != null) {
            toolList.add(queryLogsTools);
        }

        // Agentic RAG 工具（仅在 enabled 时注册为 Bean，所以需判空）
        if (agenticRagEnabled) {
            if (searchKnowledgeBaseTool != null) toolList.add(searchKnowledgeBaseTool);
            if (evaluateSearchResultsTool != null) toolList.add(evaluateSearchResultsTool);
            if (refineQueryTool != null) toolList.add(refineQueryTool);
            if (decomposeQuestionTool != null) toolList.add(decomposeQuestionTool);
            if (getSearchCapabilitiesTool != null) toolList.add(getSearchCapabilitiesTool);
        }

        // 记忆工具（仅在 memory.enabled 时注册）
        if (memoryEnabled) {
            if (recallMemoryTool != null) toolList.add(recallMemoryTool);
            if (forgetMemoryTool != null) toolList.add(forgetMemoryTool);
        }

        return toolList.toArray();
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    @CircuitBreaker(name = "dashscope-llm", fallbackMethod = "chatFallback")
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        // 重置 Agentic RAG 检索轮次计数器
        agenticRagGuard.reset();
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }

    /**
     * LLM 断路器降级方法
     * 当 DashScope LLM 调用失败或断路器打开时，返回友好错误提示
     */
    private String chatFallback(ReactAgent agent, String question, Throwable t) {
        logger.warn("[CircuitBreaker] LLM 服务降级 - question前50字符: {}, error: {}",
            question.substring(0, Math.min(50, question.length())), t.getMessage());
        return "AI 服务暂时不可用，请稍后重试。系统已自动熔断保护，预计 30 秒后恢复。";
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
