package org.example.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LLM-as-Judge 评估服务
 * AIOps 分析完成后异步调用 qwen-turbo 对报告质量进行 4 维度评分
 */
@Service
public class AIOpsEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(AIOpsEvaluator.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private TestCaseLoader testCaseLoader;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Value("${aiops.eval.enabled:true}")
    private boolean enabled;

    @Value("${aiops.eval.model:qwen-turbo}")
    private String model;

    @Value("${aiops.eval.min-pass-score:12}")
    private int minPassScore;

    @Value("${aiops.eval.sample-rate:1.0}")
    private double sampleRate;

    /**
     * 异步评估 AIOps 报告质量
     *
     * @param scenarioId 测试用例 ID（null 表示未知场景，使用通用评估）
     * @param reportText 完整报告文本
     */
    public void evaluateAsync(String scenarioId, String reportText) {
        if (!enabled) {
            logger.debug("[AIOps Eval] 评估已禁用");
            return;
        }
        if (Math.random() > sampleRate) {
            logger.debug("[AIOps Eval] 采样跳过");
            return;
        }
        if (reportText == null || reportText.trim().isEmpty()) {
            logger.debug("[AIOps Eval] 报告为空，跳过评估");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                AIOpsEvalResult result = evaluate(scenarioId, reportText);
                if (result != null) {
                    if (result.isPassed()) {
                        logger.info("[AIOps Eval] PASS scenario={} score={}/20", scenarioId, result.getTotalScore());
                    } else {
                        logger.warn("[AIOps Eval] REGRESSION scenario={} score={}/20 reasoning={}",
                                scenarioId, result.getTotalScore(), result.getReasoning());
                    }
                }
            } catch (Exception e) {
                logger.warn("[AIOps Eval] 评估异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 同步评估（内部使用）
     */
    AIOpsEvalResult evaluate(String scenarioId, String reportText) {
        TestCaseMeta meta = null;
        if (scenarioId != null && testCaseLoader != null) {
            meta = testCaseLoader.loadTestCase(scenarioId);
        }

        String judgePrompt = buildJudgePrompt(meta, reportText);
        String llmResponse = callJudgeLLM(judgePrompt);
        return parseJudgeResponse(scenarioId, llmResponse);
    }

    /**
     * 构建 Judge Prompt
     */
    private String buildJudgePrompt(TestCaseMeta meta, String reportText) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an AIOps report quality evaluator. Rate the following alert analysis report on 4 dimensions (1-5 each).

                ## Scoring Criteria
                - root_cause_accuracy (1-5): Does the root cause match any expected causes? 5=exact match, 3=partially related, 1=completely off
                - evidence_sufficiency (1-5): Does the report cite critical evidence? 5=cites 3+ points, 3=cites 1-2, 1=cites none
                - structure_completeness (1-5): Does it have alert list → root cause → remediation → conclusion? 5=complete, 3=missing one, 1=unstructured
                - actionability (1-5): Are remediation steps concrete and executable? 5=specific commands/params, 3=direction w/o details, 1=vague

                """);

        if (meta != null) {
            sb.append("## Expected Standards\n");
            sb.append("Expected root causes: ").append(meta.getExpectedRootCauses()).append("\n");
            sb.append("Critical evidence: ").append(meta.getCriticalEvidence()).append("\n\n");
        } else {
            sb.append("## Note\nNo scenario-specific standards are available. Evaluate only structure_completeness and actionability. Set root_cause_accuracy=3 and evidence_sufficiency=3 as default.\n\n");
        }

        // Truncate report to avoid token overflow (~4000 chars)
        String truncatedReport = reportText.length() > 4000
                ? reportText.substring(0, 4000) + "\n... (truncated)"
                : reportText;

        sb.append("## Report to Evaluate\n").append(truncatedReport).append("\n\n");
        sb.append("## Response Format (JSON only, no extra text)\n");
        sb.append("""
                {"root_cause_accuracy": N, "evidence_sufficiency": N, "structure_completeness": N, "actionability": N, "total_score": N, "reasoning": "brief"}
                """);

        return sb.toString();
    }

    /**
     * 调用评估 LLM 执行评估（经 ChatModelFactory 构建，支持 liteLLM 网关切换）
     */
    private String callJudgeLLM(String judgePrompt) {
        ChatModel chatModel = chatModelFactory.create(model, 0.1, 500, 0.9);

        Prompt prompt = new Prompt(new UserMessage(judgePrompt));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    /**
     * 解析 Judge 返回的 JSON
     */
    @SuppressWarnings("unchecked")
    private AIOpsEvalResult parseJudgeResponse(String scenarioId, String llmResponse) {
        try {
            String jsonStr = llmResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            Map<String, Object> map = objectMapper.readValue(jsonStr, Map.class);

            AIOpsEvalResult result = new AIOpsEvalResult();
            result.setScenarioId(scenarioId);
            result.setRootCauseAccuracy(toInt(map.get("root_cause_accuracy")));
            result.setEvidenceSufficiency(toInt(map.get("evidence_sufficiency")));
            result.setStructureCompleteness(toInt(map.get("structure_completeness")));
            result.setActionability(toInt(map.get("actionability")));

            int total = toInt(map.get("total_score"));
            if (total == 0) {
                total = result.getRootCauseAccuracy() + result.getEvidenceSufficiency()
                        + result.getStructureCompleteness() + result.getActionability();
            }
            result.setTotalScore(total);
            result.setPassed(total >= minPassScore);
            result.setReasoning((String) map.getOrDefault("reasoning", ""));

            return result;
        } catch (Exception e) {
            logger.warn("[AIOps Eval] 解析 Judge 响应失败: {}，原始: {}", e.getMessage(), llmResponse);
            return null;
        }
    }

    private int toInt(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        return 0;
    }
}
