package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 限流配置属性
 * 前缀：superbiz.rate-limit
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties("superbiz.rate-limit")
public class RateLimitConfig {

    /** 限流总开关 */
    private boolean enabled = false;

    /** 默认令牌桶容量 */
    private int defaultCapacity = 100;

    /** 默认每分钟补充令牌数 */
    private int defaultRefillRate = 10;

    /** 按端点路径设置的限流规则 */
    private Map<String, EndpointLimit> endpoints = new HashMap<>();

    /**
     * 获取指定路径的限流规则，未配置时返回 null
     */
    public EndpointLimit getEndpointLimit(String path) {
        return endpoints.get(path);
    }

    /**
     * 单个端点的限流配置
     */
    @Getter
    @Setter
    public static class EndpointLimit {
        private int capacity = 100;
        private int refillRate = 10;
    }
}
