package org.example.service.multiquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.MultiQueryProperties;
import org.example.service.DashScopeLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多角度查询变体生成器（Road C）
 * <p>
 * 输入原始问题 → 调用轻量 LLM 按多个角度生成 {@code max-variants} 个查询变体
 * （**不含原始查询**），带 Redis 缓存。
 * <p>
 * 全有或全无（all-or-nothing）：LLM 调用、JSON 解析、变体列表为空等任何失败
 * 均返回空列表（不抛异常），由上层 {@code VectorSearchService} 整路放弃、
 * 降级为两路召回。
 */
@Component
public class MultiQueryExpander {

    private static final Logger logger = LoggerFactory.getLogger(MultiQueryExpander.class);

    private static final String CACHE_KEY_PREFIX = "rag:multiquery:";

    /** 变体数量硬上限，防 Prompt 注入 / 异常大 JSON */
    private static final int MAX_VARIANTS_HARD_CAP = 10;

    /** 内置五类角度模板（可被 rag.multi-query.angle-prompt 覆盖） */
    private static final String DEFAULT_ANGLE_PROMPT = """
            你是一个检索查询扩展助手。用户将提出一个问题，你需要从多个角度生成
            {max_variants} 个不同的检索查询变体，用于向量检索召回更多相关文档。

            原始问题：{question}

            可选角度（请根据问题类型选择最合适的）：
            1. KEYWORD - 抽取核心关键词、术语、同义词组合
            2. SCENE - 从业务场景/使用场景角度重新表述
            3. SUB_QUESTION - 拆解为可独立检索的子问题
            4. CAUSE_STEP - 从原因、步骤、解决方案角度
            5. COMPARE - 从对比、差异、前后变化角度

            要求：
            - 每个变体是独立的、可直接检索的短查询（10-30 字）
            - 变体之间尽量覆盖不同语义角度，不要重复
            - 只返回 JSON 数组，不要其他内容：
            [{"query": "...", "angle": "KEYWORD", "rationale": "..."}]
            """;

    private final DashScopeLlmClient llmClient;
    private final MultiQueryProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MultiQueryExpander(DashScopeLlmClient llmClient,
                              MultiQueryProperties properties,
                              StringRedisTemplate redisTemplate) {
        this.llmClient = llmClient;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成多角度查询变体列表（不含原始查询）。
     *
     * @param originalQuery 原始用户问题
     * @return 变体列表；任何失败返回空列表（调用方整路放弃，降级两路）
     */
    public List<QueryVariant> expand(String originalQuery) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            logger.warn("[MultiQuery] 原始查询为空，放弃该路");
            return List.of();
        }
        int maxVariants = Math.min(properties.getMaxVariants(), MAX_VARIANTS_HARD_CAP);
        if (maxVariants <= 0) {
            logger.warn("[MultiQuery] max-variants 配置无效 ({}), 放弃该路", properties.getMaxVariants());
            return List.of();
        }

        // 1. 查缓存（缓存完整变体列表）
        List<QueryVariant> cached = getCached(originalQuery);
        if (cached != null) {
            logger.debug("[MultiQuery] 命中缓存, variants={}", cached.size());
            return cached;
        }

        // 2. 调用 LLM（整体超时控制，超时/异常即整路放弃）
        String llmResponse;
        try {
            llmResponse = callWithTimeout(originalQuery, maxVariants);
        } catch (Exception e) {
            logger.warn("[MultiQuery] LLM 生成变体失败，整路放弃: {}", e.getMessage());
            return List.of();
        }

        // 3. 解析变体（解析失败/为空即整路放弃）
        List<QueryVariant> variants;
        try {
            variants = parseVariants(llmResponse, maxVariants);
        } catch (Exception e) {
            logger.warn("[MultiQuery] 解析变体失败，整路放弃: {}", e.getMessage());
            return List.of();
        }
        if (variants.isEmpty()) {
            logger.warn("[MultiQuery] LLM 返回空变体列表，整路放弃");
            return List.of();
        }

        logger.info("[MultiQuery] 变体生成成功: {} 个", variants.size());
        for (QueryVariant v : variants) {
            logger.debug("[MultiQuery]   variant[{}] angle={} query=[{}] rationale=[{}]",
                    v.index(), v.angle(), truncate(v.query(), 80), truncate(v.rationale(), 80));
        }

        // 4. 写缓存（异步，失败不影响主流程）
        cacheAsync(originalQuery, variants);

        return variants;
    }

    // === LLM 调用 ===

    private String callWithTimeout(String originalQuery, int maxVariants) throws Exception {
        String prompt = buildPrompt(originalQuery, maxVariants);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                llmClient.call(properties.getModel(), prompt,
                        properties.getTemperature(), properties.getMaxTokens()));
        try {
            return future.get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // 超时/中断/执行异常统一视为该路失败（底层调用线程由 RestTemplate 自身超时兜底结束）
            logger.warn("[MultiQuery] LLM 调用超时或异常 (timeout={}s): {}",
                    properties.getTimeoutSeconds(), e.getMessage());
            throw e;
        }
    }

    private String buildPrompt(String originalQuery, int maxVariants) {
        String template = properties.getAnglePrompt();
        if (template == null || template.isBlank()) {
            template = DEFAULT_ANGLE_PROMPT;
        }
        return template
                .replace("{max_variants}", String.valueOf(maxVariants))
                .replace("{question}", originalQuery);
    }

    // === 解析 ===

    /**
     * 解析 LLM 返回的 JSON 数组，构造变体列表（截断到 maxVariants）。
     * 兼容纯数组、Markdown 代码块包裹、{@code {"variants": [...]}} 三种形态。
     */
    private List<QueryVariant> parseVariants(String llmResponse, int maxVariants) throws Exception {
        String jsonArray = extractJsonArray(llmResponse);
        if (jsonArray == null) {
            throw new IllegalArgumentException("LLM 返回中未找到 JSON 数组");
        }
        List<Map<String, Object>> items = objectMapper.readValue(jsonArray,
                new TypeReference<List<Map<String, Object>>>() {});
        if (items == null) {
            return List.of();
        }

        List<QueryVariant> variants = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> item : items) {
            if (variants.size() >= maxVariants) {
                break;
            }
            if (item == null) {
                continue;
            }
            Object query = item.get("query");
            if (query == null || query.toString().trim().isEmpty()) {
                logger.debug("[MultiQuery] 跳过空 query 变体");
                continue;
            }
            variants.add(new QueryVariant(
                    index++,
                    query.toString().trim(),
                    strOrEmpty(item.get("angle")),
                    strOrEmpty(item.get("rationale"))));
        }
        return variants;
    }

    /**
     * 提取 JSON 数组：优先第一个 '[' 到最后一个 ']'；
     * 找不到数组时兼容 {@code {"variants": [...]}} 对象形态。
     */
    private String extractJsonArray(String text) throws Exception {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        // 兼容对象形态：取 variants 字段
        int objStart = text.indexOf('{');
        int objEnd = text.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            Map<String, Object> obj = objectMapper.readValue(
                    text.substring(objStart, objEnd + 1),
                    new TypeReference<Map<String, Object>>() {});
            Object variants = obj.get("variants");
            if (variants instanceof List) {
                return objectMapper.writeValueAsString(variants);
            }
        }
        return null;
    }

    private String strOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    // === 缓存 ===

    private String buildCacheKey(String originalQuery) {
        return CACHE_KEY_PREFIX + md5(originalQuery);
    }

    private List<QueryVariant> getCached(String originalQuery) {
        if (!properties.getCache().isEnabled()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(buildCacheKey(originalQuery));
            if (json == null) {
                return null;
            }
            List<Map<String, Object>> items = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return toVariants(items);
        } catch (Exception e) {
            logger.debug("[MultiQuery] Redis 读取缓存异常，跳过缓存: {}", e.getMessage());
            return null;
        }
    }

    private void cacheAsync(String originalQuery, List<QueryVariant> variants) {
        if (!properties.getCache().isEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> items = new ArrayList<>();
                for (QueryVariant v : variants) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", v.index());
                    m.put("query", v.query());
                    m.put("angle", v.angle());
                    m.put("rationale", v.rationale());
                    items.add(m);
                }
                String key = buildCacheKey(originalQuery);
                Duration ttl = Duration.ofHours(properties.getCache().getTtlHours());
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(items), ttl);
                logger.debug("[MultiQuery] 变体列表已缓存, key={}, ttl={}h", key, ttl.toHours());
            } catch (Exception e) {
                logger.warn("[MultiQuery] Redis 写入缓存异常，跳过缓存: {}", e.getMessage());
            }
        });
    }

    private List<QueryVariant> toVariants(List<Map<String, Object>> items) {
        List<QueryVariant> variants = new ArrayList<>();
        if (items == null) {
            return variants;
        }
        int index = 1;
        for (Map<String, Object> item : items) {
            Object query = item.get("query");
            if (query == null || query.toString().trim().isEmpty()) {
                continue;
            }
            variants.add(new QueryVariant(
                    index++,
                    query.toString().trim(),
                    strOrEmpty(item.get("angle")),
                    strOrEmpty(item.get("rationale"))));
        }
        return variants;
    }

    // === 工具方法 ===

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "null";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
