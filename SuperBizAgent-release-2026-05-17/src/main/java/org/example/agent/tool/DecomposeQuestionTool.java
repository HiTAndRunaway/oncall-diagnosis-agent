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
 * 问题拆解工具（Agentic RAG）
 * <p>
 * 将复杂问题（对比/分析/多步/复合类）拆成独立子问题列表。
 * 简单事实类问题返回 type=simple，子问题列表只含原问题。
 * 每个子问题可独立进行检索。
 * <p>
 * 仅在 rag.agentic.enabled=true 时注册为 Bean。
 */
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class DecomposeQuestionTool {

    private static final Logger logger = LoggerFactory.getLogger(DecomposeQuestionTool.class);

    @Autowired
    private DashScopeLlmClient llmClient;

    @Value("${rag.agentic.decomposer-model:qwen-turbo}")
    private String model;

    @Value("${rag.agentic.max-sub-questions:5}")
    private int maxSubQuestions;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = """
            拆解复杂问题。将对比/分析/多步/复合类问题拆成独立子问题列表。
            简单事实类问题返回 type=simple，子问题列表只含原问题。
            每个子问题可独立进行检索，最后综合所有检索结果生成答案。""")
    public String decomposeQuestion(
            @ToolParam(description = "用户原始问题") String question) {

        try {
            logger.info("decomposeQuestion: question=[{}]", ToolUtils.truncate(question, 200));

            // 构建拆解 prompt
            String prompt = buildDecomposePrompt(question);

            // 调用 LLM 拆解
            String llmResponse = llmClient.call(model, prompt, 0.0, 500);

            // 解析并返回
            Map<String, Object> result = parseDecomposeResponse(llmResponse, question);

            String json = objectMapper.writeValueAsString(result);
            logger.info("decomposeQuestion 完成: type={}, subQuestions={}",
                    result.get("type"),
                    result.get("subQuestions") instanceof List<?> l ? l.size() : 0);
            return json;

        } catch (Exception e) {
            logger.warn("decomposeQuestion 异常，降级为 simple: {}", e.getMessage());
            return buildSimpleFallback(question);
        }
    }

    /**
     * 构建拆解 prompt
     */
    private String buildDecomposePrompt(String question) {
        return String.format("""
                你是一个问题分析助手。请判断以下用户问题的类型：

                问题：%s

                规则：
                1. 如果问题是简单的事实性问题（单一主题，不需要对比、不需要多步推理），返回：
                   {"type": "simple", "complexityReason": "...", "subQuestions": [{"index": 1, "query": "原问题", "reason": "无需拆解"}]}

                2. 如果问题是复杂问题（包含对比、分析、多个独立主题、需要多步推理），拆成 %d 个以内的子问题，返回：
                   {"type": "complex", "complexityReason": "...", "subQuestions": [{"index": 1, "query": "子问题1", "reason": "拆解原因"}, ...]}

                注意：
                - 只返回 JSON，不要其他内容
                - 子问题应该是独立的、可单独检索的关键词或短句
                - 子问题之间应尽量不重叠
                - 子问题数量不要超过 %d
                """, question, maxSubQuestions, maxSubQuestions);
    }

    /**
     * 解析 LLM 返回的 JSON
     */
    private Map<String, Object> parseDecomposeResponse(String llmResponse, String originalQuestion) {
        try {
            String jsonStr = ToolUtils.extractJsonBlock(llmResponse);
            Map<String, Object> parsed = objectMapper.readValue(jsonStr,
                    new TypeReference<Map<String, Object>>() {});
            // 确保必要字段存在
            if (!parsed.containsKey("type")) {
                parsed.put("type", "simple");
            }
            if (!parsed.containsKey("subQuestions")) {
                parsed.put("subQuestions",
                        List.of(Map.of("index", 1, "query", originalQuestion, "reason", "无需拆解")));
            }
            return parsed;
        } catch (Exception e) {
            logger.warn("解析拆解 LLM 返回失败: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "simple");
            fallback.put("complexityReason", "解析失败，降级为原问题");
            fallback.put("subQuestions",
                    List.of(Map.of("index", 1, "query", originalQuestion, "reason", "降级为原问题")));
            return fallback;
        }
    }

    /**
     * 降级：返回 simple 类型
     */
    private String buildSimpleFallback(String question) {
        try {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "simple");
            fallback.put("complexityReason", "拆解服务异常，降级为原问题");
            fallback.put("subQuestions",
                    List.of(Map.of("index", 1, "query", question, "reason", "降级为原问题")));
            return objectMapper.writeValueAsString(fallback);
        } catch (Exception ex) {
            return String.format(
                    "{\"type\":\"simple\",\"subQuestions\":[{\"index\":1,\"query\":\"%s\",\"reason\":\"降级\"}]}",
                    ToolUtils.escapeJson(question));
        }
    }
}
