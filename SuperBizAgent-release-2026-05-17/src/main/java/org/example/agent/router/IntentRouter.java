package org.example.agent.router;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 意图识别路由服务
 * 使用轻量 qwen-turbo 模型对用户输入进行意图分类，
 * 将请求路由到不同的处理管道（AIOps / RAG Chat / 通用 Chat）
 */
@Service
public class IntentRouter {

    private static final Logger logger = LoggerFactory.getLogger(IntentRouter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${intent.router.enabled:true}")
    private boolean enabled;

    @Value("${intent.router.model:qwen-turbo}")
    private String model;

    @Value("${intent.router.confidence-threshold:0.85}")
    private double confidenceThreshold;

    /**
     * 对用户输入进行意图分类
     *
     * @param userInput 用户原始输入文本
     * @return 分类结果（类别 + 置信度 + 理由）
     */
    public IntentResult classify(String userInput) {
        if (!enabled) {
            logger.debug("[IntentRouter] 路由已禁用，返回 GENERAL_CHAT");
            return IntentResult.fallback("routing disabled");
        }

        if (userInput == null || userInput.trim().isEmpty()) {
            return IntentResult.fallback("empty input");
        }

        try {
            String llmResponse = callClassificationLLM(userInput);
            return parseResponse(llmResponse);
        } catch (Exception e) {
            logger.warn("[IntentRouter] 分类调用失败，降级为 GENERAL_CHAT: {}", e.getMessage());
            return IntentResult.fallback("classification failed: " + e.getMessage());
        }
    }

    /**
     * 调用 qwen-turbo 进行意图分类
     */
    private String callClassificationLLM(String userInput) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();

        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(model)
                        .withTemperature(0.1)
                        .withMaxToken(200)
                        .withTopP(0.9)
                        .build())
                .build();

        String classificationPrompt = buildClassificationPrompt(userInput);
        Prompt prompt = new Prompt(new UserMessage(classificationPrompt));
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult().getOutput().getText();

        logger.debug("[IntentRouter] LLM 原始响应: {}", text);
        return text;
    }

    /**
     * 构建分类 System Prompt
     */
    private String buildClassificationPrompt(String userInput) {
        return """
                Analyze the user input and classify it into one intent category. Return ONLY valid JSON.

                Categories:
                - ALERT_DIAGNOSIS: User describes system faults, alerts, anomalies needing ops diagnosis and troubleshooting.
                - KNOWLEDGE_RETRIEVAL: User asks about internal docs, procedures, best practices, or technical solutions in general.
                - GENERAL_CHAT: General conversation, code writing, concept explanation, or non-ops requests.
                - UNCLEAR: Cannot determine the intent from the input.

                User input: "%s"

                Return: {"category": "CATEGORY_NAME", "confidence": 0.0-1.0, "reasoning": "brief reason in English"}
                """.formatted(userInput);
    }

    /**
     * 解析 LLM 返回的 JSON → IntentResult
     */
    @SuppressWarnings("unchecked")
    private IntentResult parseResponse(String llmResponse) {
        // 清洗可能的 markdown 代码块包裹
        String jsonStr = llmResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }

        Map<String, Object> map;
        try {
            map = objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) {
            logger.warn("[IntentRouter] JSON 解析失败: {}，原始响应: {}", e.getMessage(), jsonStr);
            return IntentResult.fallback("JSON parse error: " + e.getMessage());
        }

        String categoryStr = (String) map.get("category");
        double confidence = map.get("confidence") instanceof Number
                ? ((Number) map.get("confidence")).doubleValue()
                : 0.0;
        String reasoning = (String) map.getOrDefault("reasoning", "");

        IntentCategory category;
        try {
            category = IntentCategory.valueOf(categoryStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warn("[IntentRouter] 未知类别: {}, 降级为 GENERAL_CHAT", categoryStr);
            return IntentResult.fallback("unknown category: " + categoryStr);
        }

        if (confidence < confidenceThreshold && category != IntentCategory.UNCLEAR) {
            logger.info("[IntentRouter] 置信度 {} < 阈值 {}, 归类为 UNCLEAR", confidence, confidenceThreshold);
            return IntentResult.of(IntentCategory.UNCLEAR, confidence, reasoning);
        }

        logger.info("[IntentRouter] 分类结果: category={} confidence={} reasoning={}", category, confidence, reasoning);
        return IntentResult.of(category, confidence, reasoning);
    }
}
