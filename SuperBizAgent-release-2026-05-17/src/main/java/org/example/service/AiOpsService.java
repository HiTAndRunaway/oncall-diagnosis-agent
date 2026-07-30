package org.example.service;

import org.example.agent.AgentRunner;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.eval.AIOpsEvaluator;
import org.example.dto.AiOpsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * AI Ops 智能运维服务 — 轻量编排层
 * 将 Agent 构建与执行全部委托给 {@link AgentRunner}，
 * 本类仅负责流程入口和报告提取。
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    @Autowired(required = false)
    private AIOpsEvaluator aiOpsEvaluator;

    @Value("${aiops.total-timeout-seconds:300}")
    private int totalTimeoutSeconds;

    /**
     * 执行 AI Ops 告警分析流程
     * 所有 Agent 构建和执行已委托给 AgentRunner
     *
     * @return AIOps 分析结果
     */
    public AiOpsResult executeAiOpsAnalysis() {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");
        String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。";
        return agentRunner.executeOrchestration(taskPrompt);
    }

    /**
     * 从 AIOpsResult 中提取最终报告文本
     *
     * @param result AIOps 分析结果
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(AiOpsResult result) {
        if (result.isSuccess() && result.getFinalReport() != null) {
            return Optional.of(result.getFinalReport());
        }
        return Optional.empty();
    }

    /**
     * 异步触发 LLM-as-Judge 质量评估
     * 保留用于外部手动触发评估的场景；
     * 正常流程中评估已在 AgentRunner 内部自动完成。
     */
    private void triggerAsyncEvaluation(AiOpsResult result) {
        if (aiOpsEvaluator == null) {
            return;
        }
        try {
            if (result.isSuccess() && result.getFinalReport() != null) {
                aiOpsEvaluator.evaluateAsync(null, result.getFinalReport());
            }
        } catch (Exception e) {
            logger.warn("[AIOps] 触发评估失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
