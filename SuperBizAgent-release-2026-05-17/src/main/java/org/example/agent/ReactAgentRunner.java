package org.example.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
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

    @Autowired
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

    // ===== Memory tools (only when memory.enabled=true) =====

    @Autowired(required = false)
    private RecallMemoryTool recallMemoryTool;

    @Autowired(required = false)
    private ForgetMemoryTool forgetMemoryTool;

    // ===== Configuration =====

    @Autowired
    private ModelProperties modelProperties;

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

        String fullTaskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。";

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
                .outputKey("executor_feedback")
                .build();
    }

    // ========================================================================
    // Private — Tool arrays
    // ========================================================================

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
     */
    private ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
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
            String forcePrompt = String.format("""
                    你是一个企业级 SRE。之前的自动化分析流程因超时被中断。
                    请基于以下原始告警信息，结合你的专业知识，生成一份简要的告警分析报告。

                    原始告警信息：
                    %s

                    请按以下格式输出：
                    # 告警分析报告（超时终止 - 基于知识推断）

                    ---

                    ## 告警概述

                    ## 可能的根因分析（标注为"推断"而非确认）

                    ## 建议的排查步骤

                    ## 重要提醒
                    本报告因自动化分析超时而基于专家知识推断生成，未经过完整的工具调用验证，建议人工介入排查。
                    """, taskPrompt);

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
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。

                ## 最终报告输出要求（CRITICAL）

                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：

                ```
                # 告警分析报告

                ---

                ## 📋 活跃告警清单

                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |

                ---

                ## 🔍 告警根因分析1 - [告警名称]

                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]

                ### 症状描述
                [根据监控指标描述症状]

                ### 日志证据
                [引用查询到的关键日志]

                ### 根因结论
                [基于证据得出的根本原因]

                ---

                ## 🛠️ 处理方案执行1 - [告警名称]

                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]

                ### 处理建议
                [给出具体的处理建议]

                ### 预期效果
                [说明预期的效果]

                ---

                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]

                ---

                ## 📊 结论

                ### 整体评估
                [总结所有告警的整体情况]

                ### 关键发现
                - [发现1]
                - [发现2]

                ### 后续建议
                1. [建议1]
                2. [建议2]

                ### 风险评估
                [评估当前风险等级和影响范围]
                ```

                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过

                """;
    }

    /**
     * Build the Executor Agent system prompt.
     * Migrated from AiOpsService.buildExecutorPrompt().
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * Build the Supervisor Agent system prompt.
     * Migrated from AiOpsService.buildSupervisorSystemPrompt().
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }
}
