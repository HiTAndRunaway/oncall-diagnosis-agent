package org.example.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
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
import org.example.config.ModelProperties;
import org.example.dto.AgentEvent;
import org.example.dto.AiOpsResult;
import org.example.exception.LlmServiceException;
import org.example.service.AgenticRagGuard;
import org.example.service.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring AI Alibaba implementation of {@link AgentRunner}.
 * Encapsulates ReactAgent / SupervisorAgent creation and execution,
 * isolating framework-specific types from the rest of the application.
 */
@Component
public class ReactAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentRunner.class);

    // ===== LLM and Tool infrastructure =====

    @Autowired
    private LlmProvider llmProvider;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    // ===== Core tools (always available) =====

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)
    private QueryLogsTools queryLogsTools;

    // ===== Agentic RAG tools (only when rag.agentic.enabled=true) =====

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

    // ===== Agent Skills（skills.enabled=true 时由 SkillsConfig 注册，默认不注册） =====

    @Autowired(required = false)
    private SkillsAgentHook skillsAgentHook;

    // ===== Memory tools (only when memory.enabled=true) =====

    @Autowired(required = false)
    private RecallMemoryTool recallMemoryTool;

    @Autowired(required = false)
    private ForgetMemoryTool forgetMemoryTool;

    // ===== Configuration =====

    @Autowired
    private ModelProperties modelProperties;

    @Autowired
    private PromptManager promptManager;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${aiops.total-timeout-seconds:300}")
    private int totalTimeoutSeconds;

    @Value("${rag.agentic.enabled:false}")
    private boolean agenticRagEnabled;

    @Value("${memory.enabled:false}")
    private boolean memoryEnabled;

    // ===== AIOps evaluation =====

    @Autowired(required = false)
    private org.example.agent.eval.AIOpsEvaluator aiOpsEvaluator;

    // ========================================================================
    // Public API — AgentRunner interface implementation
    // ========================================================================

    /**
     * Synchronous agent execution for non-streaming chat.
     */
    @Override
    public String execute(String systemPrompt, String userMessage) {
        ReactAgent agent = buildReactAgent(systemPrompt);
        try {
            agenticRagGuard.reset();
            log.info("执行 ReactAgent.call() - 自动处理工具调用");
            var response = agent.call(userMessage);
            String answer = response.getText();
            log.info("ReactAgent 对话完成，答案长度: {}", answer.length());
            return answer;
        } catch (Exception e) {
            throw new LlmServiceException("DashScope", "Agent 执行失败: " + e.getMessage());
        }
    }

    /**
     * Streaming agent execution for SSE endpoints.
     * Bridges framework StreamingOutput events to AgentEvent DTOs.
     */
    @Override
    public Flux<AgentEvent> executeStream(String systemPrompt, String userMessage) {
        ReactAgent agent = buildReactAgent(systemPrompt);
        return Flux.create(sink -> {
            try {
                agent.stream(userMessage).subscribe(
                        output -> {
                            if (output instanceof StreamingOutput so) {
                                OutputType type = so.getOutputType();
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    String chunk = so.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        sink.next(AgentEvent.content(chunk));
                                    }
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    sink.next(AgentEvent.toolCallEnd(so.node()));
                                }
                            }
                        },
                        error -> {
                            log.error("Agent 流式执行出错", error);
                            sink.next(AgentEvent.error(error.getMessage()));
                            sink.complete();
                        },
                        () -> {
                            log.info("Agent 流式执行完成");
                            sink.complete();
                        }
                );
            } catch (Exception e) {
                log.error("Agent 流式执行初始化失败", e);
                sink.next(AgentEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * Multi-agent AIOps orchestration via SupervisorAgent.
     * Includes timeout control and fallback report generation.
     */
    @Override
    public AiOpsResult executeOrchestration(String taskPrompt) {
        log.info("开始执行 AI Ops 多 Agent 协作流程");

        ToolCallback[] toolCallbacks = getToolCallbacks();

        // 各 Agent 使用独立模型，不再共用一个 chatModel
        ReactAgent plannerAgent = buildPlannerAgent(toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(toolCallbacks);

        DashScopeChatModel supervisorModel = buildChatModel(modelProperties.getAiops().getSupervisor());
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(supervisorModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String fullTaskPrompt = promptManager.render("aiops/task-prompt", Map.of(), "zh");

        log.info("调用 Supervisor Agent 开始编排，超时限制: {} 秒", totalTimeoutSeconds);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Optional<OverAllState>> future = executor.submit(() -> {
            try {
                return supervisorAgent.invoke(fullTaskPrompt);
            } catch (Exception e) {
                log.error("AIOps Agent 执行异常", e);
                return Optional.empty();
            }
        });

        try {
            Optional<OverAllState> state = future.get(totalTimeoutSeconds, TimeUnit.SECONDS);
            log.info("AIOps Agent 编排正常完成");

            // 异步触发 LLM-as-Judge 质量评估
            triggerAsyncEvaluation(state);

            if (state.isPresent()) {
                Optional<String> report = extractFinalReport(state.get());
                if (report.isPresent()) {
                    return AiOpsResult.success(report.get(), 1);
                }
                return AiOpsResult.failed("未能从编排结果中提取报告");
            }
            return AiOpsResult.failed("AIOps 编排返回空状态");

        } catch (TimeoutException e) {
            log.warn("[AIOps] 分析超时 ({} 秒)，强制终止并生成兜底报告", totalTimeoutSeconds);
            future.cancel(true);
            return generateFallbackReport(taskPrompt);
        } catch (InterruptedException | ExecutionException e) {
            log.error("[AIOps] 分析执行异常，尝试生成兜底报告", e);
            return generateFallbackReport(taskPrompt);
        } finally {
            executor.shutdownNow();
        }
    }

    // ========================================================================
    // Private — Agent construction
    // ========================================================================

    /**
     * Build a standard ReactAgent for chat conversations.
     * Migrated from ChatService.createReactAgent().
     */
    private ReactAgent buildReactAgent(String systemPrompt) {
        DashScopeChatModel chatModel = buildChatModel(modelProperties.getChat());
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .hooks(buildHooks())
                .build();
    }

    /**
     * Build the Planner Agent for AIOps orchestration.
     * Migrated from AiOpsService.buildPlannerAgent().
     */
    private ReactAgent buildPlannerAgent(ToolCallback[] toolCallbacks) {
        DashScopeChatModel plannerModel = buildChatModel(modelProperties.getAiops().getPlanner());
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(plannerModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildAIOpsMethodToolsArray())
                .tools(toolCallbacks)
                .hooks(buildHooks())
                .outputKey("planner_plan")
                .build();
    }

    /**
     * Build the Executor Agent for AIOps orchestration.
     * Migrated from AiOpsService.buildExecutorAgent().
     */
    private ReactAgent buildExecutorAgent(ToolCallback[] toolCallbacks) {
        DashScopeChatModel executorModel = buildChatModel(modelProperties.getAiops().getExecutor());
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(executorModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildAIOpsMethodToolsArray())
                .tools(toolCallbacks)
                .hooks(buildHooks())
                .outputKey("executor_feedback")
                .build();
    }

    // ========================================================================
    // Private — Tool arrays
    // ========================================================================

    /**
     * 构建 Agent Hooks 列表（预留 Skills 接线）。
     * skills.enabled=true 时 SkillsConfig 注册 SkillsAgentHook（注入技能清单 + read_skill 工具），
     * 否则返回空列表，行为与未引入 Skills 前完全一致。
     */
    private List<AgentHook> buildHooks() {
        return skillsAgentHook != null ? List.of(skillsAgentHook) : List.of();
    }

    /**
     * Build the full method tools array for chat agents.
     * Migrated from ChatService.buildMethodToolsArray().
     */
    private Object[] buildMethodToolsArray() {
        List<Object> toolList = new ArrayList<>();
        toolList.add(dateTimeTools);
        toolList.add(internalDocsTools);
        toolList.add(queryMetricsTools);

        if (queryLogsTools != null) {
            toolList.add(queryLogsTools);
        }

        if (agenticRagEnabled) {
            if (searchKnowledgeBaseTool != null) toolList.add(searchKnowledgeBaseTool);
            if (evaluateSearchResultsTool != null) toolList.add(evaluateSearchResultsTool);
            if (refineQueryTool != null) toolList.add(refineQueryTool);
            if (decomposeQuestionTool != null) toolList.add(decomposeQuestionTool);
            if (getSearchCapabilitiesTool != null) toolList.add(getSearchCapabilitiesTool);
        }

        if (memoryEnabled) {
            if (recallMemoryTool != null) toolList.add(recallMemoryTool);
            if (forgetMemoryTool != null) toolList.add(forgetMemoryTool);
        }

        return toolList.toArray();
    }

    /**
     * Build a simplified method tools array for AIOps agents.
     * Migrated from AiOpsService.buildMethodToolsArray().
     */
    private Object[] buildAIOpsMethodToolsArray() {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    // ========================================================================
    // Private — Model construction
    // ========================================================================

    /**
     * Build a DashScopeChatModel from a {@link ModelProperties.ModelConfig}.
     */
    private DashScopeChatModel buildChatModel(ModelProperties.ModelConfig config) {
        DashScopeApi api = DashScopeApi.builder().apiKey(dashScopeApiKey).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(config.getName())
                        .withTemperature(config.getTemperature())
                        .withMaxToken(config.getMaxToken())
                        .withTopP(config.getTopP())
                        .build())
                .build();
    }

    /**
     * Build a DashScopeChatModel using the default chat configuration.
     */
    private DashScopeChatModel buildChatModel() {
        return buildChatModel(modelProperties.getChat());
    }

    // ========================================================================
    // Private — MCP tool callbacks
    // ========================================================================

    /**
     * Retrieve MCP-provided tool callbacks.
     * Migrated from ChatService.getToolCallbacks().
     * MCP 关闭时（MCP_CLIENT_ENABLED=false）provider 为空，返回空数组保证应用可启动。
     */
    private ToolCallback[] getToolCallbacks() {
        return tools != null ? tools.getToolCallbacks() : new ToolCallback[0];
    }

    // ========================================================================
    // Private — AIOps report extraction
    // ========================================================================

    /**
     * Extract the final report from an OverAllState.
     * Migrated from AiOpsService.extractFinalReport().
     */
    private Optional<String> extractFinalReport(OverAllState state) {
        log.info("开始提取最终报告...");
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);
        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            log.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            log.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * Asynchronously trigger LLM-as-Judge quality evaluation.
     * Migrated from AiOpsService.triggerAsyncEvaluation().
     */
    private void triggerAsyncEvaluation(Optional<OverAllState> stateOptional) {
        if (aiOpsEvaluator == null) {
            return;
        }
        try {
            stateOptional.ifPresent(state -> {
                Optional<String> report = extractFinalReport(state);
                report.ifPresent(r -> aiOpsEvaluator.evaluateAsync(null, r));
            });
        } catch (Exception e) {
            log.warn("[AIOps] 触发评估失败（不影响主流程）: {}", e.getMessage());
        }
    }

    // ========================================================================
    // Private — AIOps timeout fallback
    // ========================================================================

    /**
     * Generate a fallback report when AIOps analysis times out or fails.
     * Uses a standalone LLM call without tools.
     * Migrated from AiOpsService.forceFinalReport().
     */
    private AiOpsResult generateFallbackReport(String taskPrompt) {
        try {
            String forcePrompt = promptManager.render("aiops/fallback-report",
                    Map.of("taskPrompt", taskPrompt), "zh");

            String report = llmProvider.chat("你是一个企业级 SRE。", forcePrompt,
                    LlmProvider.ChatOptions.aiOps(modelProperties.getAiops().getPlanner().getName()));
            log.info("兜底报告生成成功，长度: {}", report != null ? report.length() : 0);
            return AiOpsResult.timeoutFallback(report);
        } catch (Exception e) {
            log.error("生成兜底报告失败", e);
            return AiOpsResult.failed("AIOps 分析失败，兜底报告也无法生成: " + e.getMessage());
        }
    }

    // ========================================================================
    // Private — AIOps system prompts (migrated from AiOpsService)
    // ========================================================================

    /**
     * Build the Planner Agent system prompt.
     * Migrated from AiOpsService.buildPlannerPrompt().
     */
    private String buildPlannerPrompt() {
        return promptManager.render("aiops/planner-prompt", Map.of(), "zh");
    }

    /**
     * Build the Executor Agent system prompt.
     * Migrated from AiOpsService.buildExecutorPrompt().
     */
    private String buildExecutorPrompt() {
        return promptManager.render("aiops/executor-prompt", Map.of(), "zh");
    }

    /**
     * Build the Supervisor Agent system prompt.
     * Migrated from AiOpsService.buildSupervisorSystemPrompt().
     */
    private String buildSupervisorSystemPrompt() {
        return promptManager.render("aiops/supervisor-prompt", Map.of(), "zh");
    }
}
