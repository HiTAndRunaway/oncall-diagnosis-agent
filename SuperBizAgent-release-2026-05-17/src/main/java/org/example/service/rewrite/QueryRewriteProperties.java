package org.example.service.rewrite;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 查询改写配置属性
 * <p>
 * 读取 application.yml 中 {@code rag.rewrite.*} 配置块。
 */
@Getter
@Setter
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

    /**
     * 重试配置
     */
    @Getter
    @Setter
    public static class Retry {
        /** 最大重试次数 */
        private int maxAttempts = 3;
        /** 退避配置 */
        private Backoff backoff = new Backoff();
    }

    /**
     * 退避配置
     */
    @Getter
    @Setter
    public static class Backoff {
        /** 首次重试间隔 */
        private Duration initialInterval = Duration.ofSeconds(5);
        /** 退避倍数 */
        private int multiplier = 5;
    }

    /**
     * 缓存配置
     */
    @Getter
    @Setter
    public static class Cache {
        /** 是否启用 Redis 缓存 */
        private boolean enabled = true;
        /** 缓存过期时间（小时） */
        private int ttlHours = 1;
    }
}
