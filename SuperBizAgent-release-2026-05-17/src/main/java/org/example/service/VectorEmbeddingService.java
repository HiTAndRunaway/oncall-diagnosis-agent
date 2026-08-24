package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.example.config.LiteLlmProperties;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量嵌入服务
 * <p>
 * 默认使用阿里云 DashScope Text Embedding API；
 * {@code litellm.enabled=true} 时改走 liteLLM OpenAI 兼容 {@code /v1/embeddings} 网关。
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    @Autowired
    private LiteLlmProperties liteLlmProperties;

    private TextEmbedding textEmbedding;

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(60000);
        return new RestTemplate(factory);
    }

    @PostConstruct
    public void init() {
        // liteLLM 网关模式下跳过 DashScope SDK 初始化（上游密钥集中在网关侧）
        if (liteLlmProperties.isEnabled()) {
            logger.info("liteLLM 网关模式：Embedding 走网关 {}，跳过 DashScope SDK 初始化", liteLlmProperties.getBaseUrl());
            return;
        }

        // 验证 API Key
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            logger.error("API Key 未正确配置！当前值: {}", apiKey);
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY 或在 application.yml 中配置正确的 API Key");
        }
        
        // 打印 API Key 前缀用于调试（不打印完整 Key 保证安全）
        String maskedKey = apiKey.length() > 8 ? 
            apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4) : 
            "***";
        logger.info("API Key 已加载: {}", maskedKey);
        
        // 设置全局 API Key（确保设置成功）
        Constants.apiKey = apiKey;
        
        // 验证 API Key 是否设置成功
        if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
            logger.error("Constants.apiKey 设置失败！");
            throw new IllegalStateException("API Key 设置到 Constants 失败");
        }
        
        logger.info("Constants.apiKey 已设置: {}", Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "...");
        
        // 创建 TextEmbedding 实例
        textEmbedding = new TextEmbedding();
        
        logger.info("阿里云 DashScope Embedding 服务初始化完成，模型: {}", model);
    }

    /**
     * 生成向量嵌入
     * 默认调用阿里云 DashScope Text Embedding API；
     * litellm.enabled=true 时走 liteLLM OpenAI 兼容 /v1/embeddings
     * 
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    @CircuitBreaker(name = "dashscope-embedding", fallbackMethod = "embedFallback")
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                logger.warn("内容为空，无法生成向量");
                throw new IllegalArgumentException("内容不能为空");
            }

            // liteLLM 网关模式
            if (liteLlmProperties.isEnabled()) {
                List<List<Float>> embeddings = generateEmbeddingsViaGateway(Collections.singletonList(content));
                return embeddings.isEmpty() ? Collections.emptyList() : embeddings.get(0);
            }

            logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());
            
            // 确保 API Key 已设置（防止被其他地方覆盖）
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                logger.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }
            
            logger.debug("调用 API 前 Constants.apiKey: {}", 
                Constants.apiKey != null ? Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "..." : "null");

            // 构建请求参数
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(Collections.singletonList(content))
                    .build();

            // 调用 API
            TextEmbeddingResult result = textEmbedding.call(param);

            // 检查结果
            List<Float> floatEmbedding = getFloats(result);

            logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}", 
                content.length(), floatEmbedding.size());

            return floatEmbedding;

        } catch (NoApiKeyException e) {
            logger.error("API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static List<Float> getFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope API 返回空结果");
        }

        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> embeddings = output.getEmbeddings();

        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope API 返回空向量列表");
        }

        // 获取第一个文本的向量
        List<Double> embeddingDoubles = embeddings.get(0).getEmbedding();

        // 转换为 List<Float>
        List<Float> floatEmbedding = new ArrayList<>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            floatEmbedding.add(value.floatValue());
        }
        return floatEmbedding;
    }

    /**
     * 断路器降级方法：返回零向量并标记需要重新索引
     */
    @SuppressWarnings("unused")
    private List<Float> embedFallback(String content, Throwable t) {
        logger.warn("[CircuitBreaker] Embedding 降级 - 返回零向量，content前50字符: {}, error: {}",
                content != null ? content.substring(0, Math.min(50, content.length())) : "null",
                t.getMessage());
        List<Float> zeroVector = new ArrayList<>(1024);
        for (int i = 0; i < 1024; i++) {
            zeroVector.add(0.0f);
        }
        return zeroVector;
    }

    /**
     * 批量生成向量嵌入
     * 默认调用阿里云 DashScope Text Embedding API；
     * litellm.enabled=true 时走 liteLLM OpenAI 兼容 /v1/embeddings
     * 
     * @param contents 文本内容列表
     * @return 向量嵌入列表
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                logger.warn("内容列表为空，无法生成向量");
                return Collections.emptyList();
            }

            logger.info("开始批量生成向量嵌入, 数量: {}", contents.size());

            // liteLLM 网关模式
            if (liteLlmProperties.isEnabled()) {
                return generateEmbeddingsViaGateway(contents);
            }
            
            // 确保 API Key 已设置
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                logger.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }

            // 构建请求参数 - 批量输入
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(contents)
                    .build();

            // 调用 API
            TextEmbeddingResult result = textEmbedding.call(param);

            // 检查结果
            if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
                throw new RuntimeException("批量 DashScope API 返回空结果");
            }

            List<TextEmbeddingResultItem> embeddingItems = result.getOutput().getEmbeddings();
            
            if (embeddingItems.isEmpty()) {
                throw new RuntimeException("批量 DashScope API 返回空向量列表");
            }

            // 转换结果
            List<List<Float>> embeddings = new ArrayList<>();
            for (TextEmbeddingResultItem item : embeddingItems) {
                List<Double> embeddingDoubles = item.getEmbedding();
                List<Float> embedding = new ArrayList<>(embeddingDoubles.size());
                for (Double value : embeddingDoubles) {
                    embedding.add(value.floatValue());
                }
                embeddings.add(embedding);
            }

            logger.info("成功批量生成向量嵌入, 数量: {}, 维度: {}", 
                embeddings.size(), 
                embeddings.isEmpty() ? 0 : embeddings.get(0).size());

            return embeddings;

        } catch (NoApiKeyException e) {
            logger.error("批量调用时 API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     * 
     * @param query 查询文本
     * @return 向量嵌入
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    /**
     * 计算两个向量的余弦相似度
     * 
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 余弦相似度 [-1, 1]
     */
    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 通过 liteLLM 网关（OpenAI 兼容 /v1/embeddings）批量生成向量
     *
     * @param texts 文本列表
     * @return 向量嵌入列表（与输入顺序一致）
     */
    @SuppressWarnings("unchecked")
    private List<List<Float>> generateEmbeddingsViaGateway(List<String> texts) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(liteLlmProperties.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        String url = liteLlmProperties.getBaseUrl() + "/v1/embeddings";

        logger.debug("调用 liteLLM Embedding 网关: {}, 文本数: {}", url, texts.size());

        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
        if (response.getBody() == null) {
            throw new RuntimeException("liteLLM Embedding 网关返回空响应");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("liteLLM Embedding 网关返回空向量列表");
        }

        List<List<Float>> embeddings = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> item = data.get(i);
            List<Number> numbers = (List<Number>) item.get("embedding");
            if (numbers == null || numbers.isEmpty()) {
                throw new RuntimeException("liteLLM Embedding 网关返回的 data[" + i + "] 缺少 embedding 字段");
            }
            List<Float> embedding = new ArrayList<>(numbers.size());
            for (Number n : numbers) {
                embedding.add(n.floatValue());
            }
            embeddings.add(embedding);
        }

        logger.info("liteLLM 网关批量生成向量完成, 数量: {}, 维度: {}",
                embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).size());
        return embeddings;
    }
}
