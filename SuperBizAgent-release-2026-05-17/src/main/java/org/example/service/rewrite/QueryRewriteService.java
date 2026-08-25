package org.example.service.rewrite;

import jakarta.annotation.PostConstruct;
import org.example.config.ChatModelFactory;
import org.example.config.ModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 查询改写协调服务
 * <p>
 * 职责：
 * <ol>
 *   <li>根据配置选择策略实例</li>
 *   <li>Redis 缓存管理（读/写）</li>
 *   <li>LLM 调用 + 超时重试（指数退避）+ 降级</li>
 *   <li>日志记录</li>
 * </ol>
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String CACHE_KEY_PREFIX = "rag:rewrite:";

    private final QueryRewriteProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ChatModelFactory chatModelFactory;
    private final ModelProperties modelProperties;

    private QueryRewriteStrategy strategy;

    public QueryRewriteService(QueryRewriteProperties properties,
                               StringRedisTemplate redisTemplate,
                               ChatModelFactory chatModelFactory,
                               ModelProperties modelProperties) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.chatModelFactory = chatModelFactory;
        this.modelProperties = modelProperties;
    }

    @PostConstruct
    public void init() {
        QueryRewriteProperties.StrategyType strategyType = properties.getStrategy();

        this.strategy = switch (strategyType) {
            case PROMPT_REWRITE -> {
                logger.info("查询改写策略初始化为: prompt_rewrite (策略1)");
                yield new PromptRewriteStrategy(createRewriteChatModel());
            }
            case HYPOTHETICAL_ANSWER -> {
                logger.info("查询改写策略初始化为: hypothetical_answer (策略2)");
                yield new HypotheticalAnswerStrategy(createRewriteChatModel());
            }
            case DETAIL_ABSTRACT -> {
                logger.info("查询改写策略初始化为: detail_abstract (策略3)");
                yield new DetailAbstractStrategy(createRewriteChatModel());
            }
            case DIRECT -> {
                logger.info("查询改写策略初始化为: direct (策略4，默认)");
                yield new DirectStrategy();
            }
        };
    }

    /**
     * 改写查询文本（主入口，无反馈）
     *
     * @param originalQuery 原始用户问题
     * @return 改写后的文本（降级时返回原始 query）
     */
    public String rewrite(String originalQuery) {
        return rewriteWithFeedback(originalQuery, null);
    }

    /**
     * 改写查询文本（可结合检索评估反馈）——Agentic RAG refine 环节专用入口。
     * <p>
     * 传入非空 {@code feedback} 时，改写策略会利用评估结果生成更有针对性的查询；
     * {@code feedback} 为 null/空时行为等同于 {@link #rewrite(String)}。
     *
     * @param originalQuery 原始用户问题
     * @param feedback      检索评估反馈文本（来自 evaluateSearchResults 的 summary/evaluations），可为 null
     * @return 改写后的文本（降级时返回原始 query）
     */
    public String rewriteWithFeedback(String originalQuery, String feedback) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return originalQuery;
        }

        // 无需 LLM 的策略跳过缓存和重试（feedback 对无 LLM 策略无意义）
        if (!strategy.requiresLlm()) {
            return strategy.rewrite(originalQuery);
        }

        // 检查 Redis 缓存（key 含 feedback，保证不同反馈结果不互相污染）
        String cached = getCachedRewrite(originalQuery, feedback);
        if (cached != null) {
            return cached;
        }

        // 调用 LLM 改写（含重试和降级）
        String rewritten = rewriteWithRetry(originalQuery, feedback);

        // 写入缓存
        if (!rewritten.equals(originalQuery)) {
            cacheRewriteAsync(originalQuery, feedback, rewritten);
        }

        return rewritten;
    }

    // === 缓存 ===

    private String buildCacheKey(String originalQuery, String feedback) {
        String hash = md5(originalQuery);
        String strategyName = properties.getStrategy().name().toLowerCase();
        // 有反馈时追加反馈摘要，避免不同反馈互相污染缓存；无反馈保持原 key 格式以兼容历史缓存
        if (feedback == null || feedback.trim().isEmpty()) {
            return CACHE_KEY_PREFIX + strategyName + ":" + hash;
        }
        return CACHE_KEY_PREFIX + strategyName + ":" + hash + ":" + md5(feedback);
    }

    private String getCachedRewrite(String originalQuery, String feedback) {
        if (!properties.getCache().isEnabled()) {
            return null;
        }
        try {
            String key = buildCacheKey(originalQuery, feedback);
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("查询改写命中缓存, strategy={}, key={}", properties.getStrategy(), key);
                return value;
            }
        } catch (Exception e) {
            logger.warn("Redis 读取缓存异常，跳过缓存: {}", e.getMessage());
        }
        return null;
    }

    private void cacheRewriteAsync(String originalQuery, String feedback, String rewritten) {
        if (!properties.getCache().isEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String key = buildCacheKey(originalQuery, feedback);
                Duration ttl = Duration.ofHours(properties.getCache().getTtlHours());
                redisTemplate.opsForValue().set(key, rewritten, ttl);
                logger.debug("查询改写结果已缓存, key={}, ttl={}h", key, properties.getCache().getTtlHours());
            } catch (Exception e) {
                logger.warn("Redis 写入缓存异常，跳过缓存: {}", e.getMessage());
            }
        });
    }

    // === LLM 调用 + 重试 + 降级 ===

    private String rewriteWithRetry(String originalQuery, String feedback) {
        int maxAttempts = properties.getRetry().getMaxAttempts();
        long backoffMs = properties.getRetry().getBackoff().getInitialInterval().toMillis();
        int multiplier = properties.getRetry().getBackoff().getMultiplier();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 传入 feedback，支持策略实现"结合评估反馈改写"（无反馈时策略内部退化为普通改写）
                String rewritten = strategy.rewrite(originalQuery, feedback);

                // 如果 LLM 返回了与原 query 不同的有效内容，直接返回
                if (rewritten != null && !rewritten.trim().isEmpty() && !rewritten.equals(originalQuery)) {
                    logger.info("查询改写成功, strategy={}, original=[{}] → rewritten=[{}], useFeedback={}",
                            properties.getStrategy(),
                            strategy.truncate(originalQuery),
                            strategy.truncate(rewritten),
                            feedback != null && !feedback.trim().isEmpty());
                    return rewritten;
                }

                // LLM 返回了空或相同内容（视为无效改写），降级
                logger.warn("查询改写返回无效结果，降级为 direct, strategy={}", properties.getStrategy());
                return originalQuery;

            } catch (Exception e) {
                if (isTimeoutException(e)) {
                    // 超时类异常 → 指数退避重试
                    if (attempt < maxAttempts) {
                        logger.warn("查询改写超时, 第{}次重试, 等待{}ms, strategy={}",
                                attempt, backoffMs, properties.getStrategy());
                        sleep(backoffMs);
                        backoffMs *= multiplier;
                    } else {
                        logger.warn("查询改写超时, 已达最大重试次数({}), 降级为 direct, strategy={}",
                                maxAttempts, properties.getStrategy());
                        return fallback(originalQuery, "超时重试" + maxAttempts + "次后仍失败");
                    }
                } else {
                    // 业务类异常 → 直接降级
                    logger.warn("查询改写降级为 direct, strategy={}, reason={}",
                            properties.getStrategy(), e.getMessage());
                    return fallback(originalQuery, e.getMessage());
                }
            }
        }

        // 不应该到达这里，但作为安全兜底
        return fallback(originalQuery, "未知原因");
    }

    private String fallback(String originalQuery, String reason) {
        logger.warn("查询改写降级为 direct, originalQuery=[{}], reason={}",
                strategy.truncate(originalQuery), reason);
        return originalQuery;
    }

    // === 工具方法 ===

    /**
     * 判断是否为超时类异常
     */
    private boolean isTimeoutException(Throwable e) {
        if (e == null) return false;
        String className = e.getClass().getName();
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        return className.contains("Timeout")
                || className.contains("TimeOut")
                || message.contains("timeout")
                || message.contains("timed out")
                || message.contains("read timed out")
                || isTimeoutException(e.getCause());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

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

    /**
     * 创建改写用的轻量 ChatModel（经 ChatModelFactory 构建，支持 liteLLM 网关切换）
     */
    private ChatModel createRewriteChatModel() {
        return chatModelFactory.create(modelProperties.getRewrite());
    }
}
