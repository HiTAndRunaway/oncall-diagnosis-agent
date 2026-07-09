package org.example.service.rewrite;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 查询改写配置属性
 * <p>
 * 读取 application.yml 中 {@code rag.rewrite.*} 配置块。
 */
@Configuration
@ConfigurationProperties(prefix = "rag.rewrite")
public class QueryRewriteProperties {

    /** 策略选择，默认 direct（不调用 LLM） */
    private StrategyType strategy = StrategyType.DIRECT;

    /** 改写用的轻量 LLM 模型 */
    private String model = "qwen-turbo";

    /** 重试配置 */
    private Retry retry = new Retry();

    /** 缓存配置 */
    private Cache cache = new Cache();

    // === 枚举 ===

    public enum StrategyType {
        PROMPT_REWRITE,
        HYPOTHETICAL_ANSWER,
        DETAIL_ABSTRACT,
        DIRECT
    }

    // === 内部类 ===

    public static class Retry {
        /** 最大重试次数 */
        private int maxAttempts = 3;
        /** 退避配置 */
        private Backoff backoff = new Backoff();

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Backoff getBackoff() {
            return backoff;
        }

        public void setBackoff(Backoff backoff) {
            this.backoff = backoff;
        }
    }

    public static class Backoff {
        /** 首次重试间隔 */
        private Duration initialInterval = Duration.ofSeconds(5);
        /** 退避倍数 */
        private int multiplier = 5;

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public int getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(int multiplier) {
            this.multiplier = multiplier;
        }
    }

    public static class Cache {
        /** 是否启用 Redis 缓存 */
        private boolean enabled = true;
        /** 缓存过期时间（小时） */
        private int ttlHours = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(int ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    // === getter/setter ===

    public StrategyType getStrategy() {
        return strategy;
    }

    public void setStrategy(StrategyType strategy) {
        this.strategy = strategy;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }
}
