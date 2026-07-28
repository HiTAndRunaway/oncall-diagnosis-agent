package org.example.agent.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge AIOps report quality evaluator.
 * <p>
 * Evaluates AIOps analysis reports across four dimensions
 * ({@link EvalDimension}) using a separate, lightweight LLM call
 * (qwen-turbo by default). Runs asynchronously — never blocks the main
 * analysis flow. If evaluation is disabled or fails, the caller receives
 * null without exception.
 */
@Service
public class AIOpsEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(AIOpsEvaluator.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TestCaseLoader testCaseLoader;

    @Value("${aiops.eval.enabled:false}")
    private boolean evalEnabled;

    @Value("${aiops.eval.model:qwen-turbo}")
    private String evalModel;

    @Value("${aiops.eval.min-pass-score:12}")
    private int minPassScore;

    @Value("${aiops.eval.sample-rate:1.0}")
    private double sampleRate;

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${aiops.eval.dashscope-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String dashscopeBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Evaluate an AIOps report asynchronously.
     * <p>
     * Fire-and-forget pattern — callers should invoke this and discard the
     * returned future. Results are logged.
     *
     * @param scenarioId test case scenario ID (null for generic evaluation)
     * @param reportText the AIOps analysis report to evaluate
     */
    public void evaluateAsync(String scenarioId, String reportText) {
        try {
            AIOpsEvalResult result = evaluate(scenarioId, reportText);
            if (result != null) {
                if (result.isPassed()) {
                    logger.info("[AIOps Eval] PASS — scenario={}, totalScore={}, reasoning={}",
                            result.getScenarioId(), result.getTotalScore(), result.getReasoning());
                } else {
                    logger.warn("[AIOps Eval] REGRESSION — scenario={}, totalScore={}, minPass={}, reasoning={}",
                            result.getScenarioId(), result.getTotalScore(), minPassScore, result.getReasoning());
                }
            }
        } catch (Exception e) {
            logger.warn("[AIOps Eval] Async evaluation failed: {}", e.getMessage());
        }
    }

    /**
     * Evaluate an AIOps report synchronously.
     *
     * @param scenarioId test case scenario ID (null for generic evaluation)
     * @param reportText the AIOps analysis report to evaluate
     * @return evaluation result, or null if evaluation is disabled, skipped, or fails
     */
    public AIOpsEvalResult evaluate(String scenarioId, String reportText) {
        if (!evalEnabled) {
            logger.debug("[AIOps Eval] Evaluation disabled — skipping");
            return null;
        }

        if (reportText == null || reportText.isBlank()) {
            logger.warn("[AIOps Eval] Empty report text — skipping evaluation");
            return null;
        }

        // Sample-rate check
        if (sampleRate < 1.0 && Math.random() > sampleRate) {
            logger.debug("[AIOps Eval] Skipped by sample rate ({})", sampleRate);
            return null;
        }

        if (apiKey == null || apiKey.isBlank()) {
            logger.warn("[AIOps Eval] No DashScope API key configured — skipping evaluation");
            return null;
        }

        try {
            TestCaseLoader.TestCaseMeta meta = null;
            if (scenarioId != null && !scenarioId.isBlank()) {
                meta = testCaseLoader.loadTestCase(scenarioId);
            }

            String judgePrompt = buildJudgePrompt(meta, reportText);
            String responseJson = callDashScopeLLM(judgePrompt);

            AIOpsEvalResult result = parseEvalResponse(scenarioId, responseJson, meta);
            return result;

        } catch (Exception e) {
            logger.warn("[AIOps Eval] Evaluation failed — returning null. Error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build the LLM Judge prompt based on whether a test case scenario is available.
     *
     * @param meta test case metadata (nullable — if null, evaluates only structure + actionability)
     * @param reportText the AIOps report text to evaluate
     * @return the judge prompt string
     */
    private String buildJudgePrompt(TestCaseLoader.TestCaseMeta meta, String reportText) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an AIOps report quality evaluator. Rate the following alert analysis report on 4 dimensions (1-5 each).\n\n");
        sb.append("## Scoring Criteria\n");
        sb.append("- root_cause_accuracy (1-5): Does the root cause match any of the expected causes? 5=exact match, 3=partially related, 1=completely off\n");
        sb.append("- evidence_sufficiency (1-5): Does the report cite the critical evidence points? 5=cites 3+, 3=cites 1-2, 1=cites none\n");
        sb.append("- structure_completeness (1-5): Does it have all sections (alert list -> root cause -> remediation -> conclusion)? 5=complete, 3=missing one, 1=unstructured\n");
        sb.append("- actionability (1-5): Are remediation steps concrete and executable? 5=specific commands/params, 3=direction without details, 1=vague\n");

        if (meta != null) {
            sb.append("\n## Expected Standards\n");
            sb.append("Expected root causes: ").append(meta.getExpectedRootCauses()).append("\n");
            sb.append("Critical evidence: ").append(meta.getCriticalEvidence()).append("\n");
        }

        sb.append("\n## Report to Evaluate\n");
        // Truncate report to avoid exceeding token limits (max ~30000 chars)
        String truncated = reportText.length() > 30000 ? reportText.substring(0, 30000) + "\n\n... [report truncated]" : reportText;
        sb.append(truncated).append("\n");

        sb.append("\n## Response Format (JSON only, no extra text)\n");
        sb.append("{\"root_cause_accuracy\": N, \"evidence_sufficiency\": N, \"structure_completeness\": N, \"actionability\": N, \"total_score\": N, \"reasoning\": \"brief\"}\n");

        return sb.toString();
    }

    /**
     * Call the DashScope LLM with the judge prompt.
     *
     * @param prompt the judge prompt to send
     * @return the LLM response content text
     * @throws RuntimeException on any API or parsing error
     */
    private String callDashScopeLLM(String prompt) {
        try {
            String url = dashscopeBaseUrl + "/chat/completions";

            Map<String, Object> requestBody = Map.of(
                    "model", evalModel,
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.0,
                    "max_tokens", 500
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.debug("DashScope API error response body (first 500 chars): {}",
                        response.body().length() > 500 ? response.body().substring(0, 500) : response.body());
                throw new RuntimeException("DashScope API returned status " + response.statusCode());
            }

            // Extract content from the response
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("DashScope response has no choices");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                throw new RuntimeException("DashScope response has no message");
            }

            String content = (String) message.get("content");
            if (content == null || content.isBlank()) {
                throw new RuntimeException("DashScope response content is empty");
            }

            return content.trim();

        } catch (RuntimeException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call DashScope LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the LLM JSON response into an AIOpsEvalResult.
     */
    private AIOpsEvalResult parseEvalResponse(String scenarioId, String jsonText, TestCaseLoader.TestCaseMeta meta) {
        // Extract JSON from possible markdown fences
        String cleaned = jsonText;
        if (cleaned.contains("```")) {
            int start = cleaned.indexOf("{");
            int end = cleaned.lastIndexOf("}");
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }

        AIOpsEvalResult result = new AIOpsEvalResult();
        result.setScenarioId(scenarioId);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);

            result.setRootCauseAccuracy(getIntValue(map, "root_cause_accuracy"));
            result.setEvidenceSufficiency(getIntValue(map, "evidence_sufficiency"));
            result.setStructureCompleteness(getIntValue(map, "structure_completeness"));
            result.setActionability(getIntValue(map, "actionability"));

            int totalScore = getIntValue(map, "total_score");
            if (totalScore <= 0) {
                // Auto-calculate if LLM didn't provide total
                totalScore = result.getRootCauseAccuracy() + result.getEvidenceSufficiency()
                        + result.getStructureCompleteness() + result.getActionability();
            }
            result.setTotalScore(totalScore);

            Object reasoning = map.get("reasoning");
            result.setReasoning(reasoning instanceof String ? (String) reasoning : "");

            int effectiveMinScore = (meta != null) ? meta.getMinScore() : this.minPassScore;
            result.setPassed(totalScore >= effectiveMinScore);

        } catch (JsonProcessingException e) {
            logger.warn("[AIOps Eval] Failed to parse LLM JSON response: {}", cleaned);
            result.setRootCauseAccuracy(0);
            result.setEvidenceSufficiency(0);
            result.setStructureCompleteness(0);
            result.setActionability(0);
            result.setTotalScore(0);
            result.setReasoning("Failed to parse evaluation response");
            result.setPassed(false);
        }

        return result;
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

}
