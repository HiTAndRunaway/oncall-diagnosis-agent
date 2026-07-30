package org.example.service;

import org.example.agent.AgentRunner;
import org.example.dto.AiOpsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
}
