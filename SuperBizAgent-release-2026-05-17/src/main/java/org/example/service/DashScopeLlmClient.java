package org.example.service;

import org.example.config.LiteLlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM HTTP 客户端（通用）
 * <p>
 * 默认封装对 DashScope Text Generation API 的 HTTP 调用，
 * 供 EvaluateSearchResultsTool / DecomposeQuestionTool / MemoryExtractor 复用。
 * <p>
 * {@code litellm.enabled=true} 时改走 liteLLM OpenAI 兼容 {@code /v1/chat/completions} 网关。
 */
@Component
public class DashScopeLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeLlmClient.class);

    /** DashScope Text Generation API 端点 */
    private static final String DASHSCOPE_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Autowired
    private LiteLlmProperties liteLlmProperties;

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);  // 10 秒连接超时
        factory.setReadTimeout(60000);     // 60 秒读取超时
        return new RestTemplate(factory);
    }

    /**
     * 调用 LLM，返回 message.content 文本
     *
     * @param model       模型名称
     * @param prompt      用户提示词
     * @param temperature 温度参数
     * @param maxTokens   最大输出 token 数
     * @return LLM 返回的文本内容
     */
    public String call(String model, String prompt, double temperature, int maxTokens) {
        // liteLLM 网关模式
        if (liteLlmProperties.isEnabled()) {
            return callGateway(model, List.of(Map.of("role", "user", "content", prompt)),
                    temperature, maxTokens);
        }

        // 构建请求体
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);

        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        input.put("messages", messages);
        requestBody.put("input", input);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("result_format", "message");
        parameters.put("temperature", temperature);
        parameters.put("max_tokens", maxTokens);
        requestBody.put("parameters", parameters);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // 发送请求
        ResponseEntity<Map> response = restTemplate.postForEntity(DASHSCOPE_API_URL, entity, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("DashScope API 返回空响应");
        }

        // 提取 output.choices[0].message.content
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.getBody().get("output");
        if (output == null) {
            throw new RuntimeException("DashScope API 返回无 output 字段");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("DashScope API 返回无 choices");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("DashScope API 返回无 message");
        }
        return (String) message.get("content");
    }

    /**
     * 调用 LLM，支持 system prompt + user message
     *
     * @param model        模型名称
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param temperature  温度参数
     * @param maxTokens    最大输出 token 数
     * @return LLM 返回的文本内容
     */
    @SuppressWarnings("unchecked")
    public String callWithSystemPrompt(String model, String systemPrompt,
                                        String userMessage, double temperature, int maxTokens) {
        // liteLLM 网关模式
        if (liteLlmProperties.isEnabled()) {
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userMessage));
            return callGateway(model, messages, temperature, maxTokens);
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);

        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        input.put("messages", messages);
        requestBody.put("input", input);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("result_format", "message");
        parameters.put("temperature", temperature);
        parameters.put("max_tokens", maxTokens);
        requestBody.put("parameters", parameters);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(DASHSCOPE_API_URL, entity, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("DashScope API 返回空响应");
        }

        Map<String, Object> output = (Map<String, Object>) response.getBody().get("output");
        if (output == null) {
            throw new RuntimeException("DashScope API 返回无 output 字段");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("DashScope API 返回无 choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("DashScope API 返回无 message");
        }
        return (String) message.get("content");
    }

    /**
     * 通过 liteLLM 网关（OpenAI 兼容 /v1/chat/completions）调用 LLM
     */
    @SuppressWarnings("unchecked")
    private String callGateway(String model, List<Map<String, String>> messages,
                               double temperature, int maxTokens) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(liteLlmProperties.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        String url = liteLlmProperties.getBaseUrl() + "/v1/chat/completions";

        logger.debug("调用 liteLLM 聊天网关: {}, model: {}", url, model);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("liteLLM 网关返回空响应");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("liteLLM 网关返回无 choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("liteLLM 网关返回无 message");
        }
        return (String) message.get("content");
    }
}
