package org.example.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.DashScopeLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索结果相关性评估工具（Agentic RAG）
 * <p>
 * 用轻量 LLM 对每条检索结果评估与查询的相关性（0-1 分），
 * 返回评估报告供 Agent 判断是否需要改写查询重新检索。
 * <p>
 * 仅在 rag.agentic.enabled=true 时注册为 Bean。
 */
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class EvaluateSearchResultsTool {

    private static final Logger logger = LoggerFactory.getLogger(EvaluateSearchResultsTool.class);

    @Autowired
    private DashScopeLlmClient llmClient;

    @Value("${rag.agentic.evaluator-model:qwen-turbo}")
    private String model;

    @Value("${rag.agentic.min-relevance-score:0.6}")
    private double minRelevanceScore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = """
            评估搜索结果的相关性。对每条结果用轻量 LLM 评 0-1 分，
            返回评估报告（含 overallRelevance 和 recommendation: PROCEED/REFINE/DECOMPOSE）。
            当 overallRelevance < 0.6 时建议 refineQuery 后重新检索。""")
    public String evaluateSearchResults(
            @ToolParam(description = "原始查询文本") String query,
            @ToolParam(description = "searchKnowledgeBase 返回的完整 JSON 结果") String resultsJson) {

        try {
            // 解析输入，提取文档内容列表
            List<String> docContents = extractDocContents(resultsJson);
            if (docContents.isEmpty()) {
                logger.warn("evaluateSearchResults: 输入结果为空");
                return "{\"overallRelevance\":0.0,\"summary\":\"无搜索结果可评估\"," +
                        "\"evaluations\":[],\"recommendation\":\"REFINE\"}";
            }

            logger.info("evaluateSearchResults: 评估 {} 条结果, query=[{}]", docContents.size(), query);

            // 构建评估 prompt
            String prompt = buildEvaluationPrompt(query, docContents);

            // 调用轻量 LLM 评估
            String llmResponse = llmClient.call(model, prompt, 0.0, 500);

            // 解析 LLM 返回的评估结果
            Map<String, Object> evaluation = parseEvaluationResponse(llmResponse);

            // 加入整体评估摘要和推荐
            double overall = computeOverallScore(evaluation);
            evaluation.put("overallRelevance", Math.round(overall * 100.0) / 100.0);
            evaluation.put("recommendation",
                    overall >= minRelevanceScore ? "PROCEED" : "REFINE");

            String json = objectMapper.writeValueAsString(evaluation);
            logger.info("evaluateSearchResults 完成: overallRelevance={}, recommendation={}",
                    evaluation.get("overallRelevance"), evaluation.get("recommendation"));
            return json;

        } catch (Exception e) {
            logger.warn("evaluateSearchResults 异常，降级为 PROCEED: {}", e.getMessage());
            return "{\"overallRelevance\":0.5,\"summary\":\"评估服务暂时不可用，跳过评估\"," +
                    "\"evaluations\":[],\"recommendation\":\"PROCEED\"}";
        }
    }

    /**
     * 从 searchKnowledgeBase 返回的 JSON 中提取文档内容列表
     */
    private List<String> extractDocContents(String resultsJson) {
        java.util.List<String> contents = new java.util.ArrayList<>();
        try {
            Map<String, Object> parsed = objectMapper.readValue(resultsJson,
                    new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) parsed.get("results");
            if (results != null) {
                for (Map<String, Object> r : results) {
                    Object content = r.get("content");
                    if (content != null) {
                        // 截断过长的内容，节省 token
                        String text = content.toString();
                        if (text.length() > 500) {
                            text = text.substring(0, 500) + "...";
                        }
                        contents.add(text);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("解析检索结果 JSON 失败: {}", e.getMessage());
        }
        return contents;
    }

    /**
     * 构建评估用的 prompt
     */
    private String buildEvaluationPrompt(String query, List<String> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个搜索结果质量评估助手。请评估以下文档与查询的相关性。\n\n");
        sb.append("查询：").append(query).append("\n\n");

        for (int i = 0; i < documents.size(); i++) {
            sb.append("--- 文档 ").append(i).append(" ---\n");
            sb.append(documents.get(i)).append("\n\n");
        }

        sb.append("请以 JSON 格式返回评估结果，格式如下（只返回 JSON，不要其他内容）：\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"一句话总结整体相关性\",\n");
        sb.append("  \"evaluations\": [\n");
        sb.append("    {\"index\": 0, \"relevance\": 0.92, \"verdict\": \"HIGHLY_RELEVANT\", \"reason\": \"直接相关\"},\n");
        sb.append("    ...\n");
        sb.append("  ]\n");
        sb.append("}\n\n");
        sb.append("评分标准：0.0-0.3=NOT_RELEVANT, 0.3-0.6=PARTIALLY_RELEVANT, 0.6-0.8=RELEVANT, 0.8-1.0=HIGHLY_RELEVANT");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON，提取 evaluations 和 summary
     */
    private Map<String, Object> parseEvaluationResponse(String llmResponse) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 尝试提取 JSON 片段（LLM 可能在 JSON 前后加了说明文字）
            String jsonStr = ToolUtils.extractJsonBlock(llmResponse);
            Map<String, Object> parsed = objectMapper.readValue(jsonStr,
                    new TypeReference<Map<String, Object>>() {});
            result.put("summary", parsed.getOrDefault("summary", "评估完成"));
            result.put("evaluations", parsed.getOrDefault("evaluations", List.of()));
        } catch (Exception e) {
            logger.warn("解析评估 LLM 返回失败，使用默认值: {}", e.getMessage());
            result.put("summary", "评估解析失败");
            result.put("evaluations", List.of());
        }
        return result;
    }

    /**
     * 计算整体相关性分数（所有评估的平均值）
     */
    private double computeOverallScore(Map<String, Object> evaluation) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evals =
                (List<Map<String, Object>>) evaluation.get("evaluations");
        if (evals == null || evals.isEmpty()) {
            // 没有评估结果，保守返回 0.5
            return 0.5;
        }
        double sum = 0;
        for (Map<String, Object> e : evals) {
            Object scoreObj = e.get("relevance");
            if (scoreObj instanceof Number num) {
                sum += num.doubleValue();
            }
        }
        return evals.isEmpty() ? 0.5 : sum / evals.size();
    }
}
