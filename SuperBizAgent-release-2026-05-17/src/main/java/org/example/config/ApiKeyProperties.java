package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 认证配置属性
 * 前缀：superbiz.security
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties("superbiz.security")
public class ApiKeyProperties {

    /** 全局安全开关，false 时放行所有请求 */
    private boolean enabled = false;

    /** API Key 请求头名称，默认 X-API-Key */
    private String apiKeyHeader = "X-API-Key";

    /** 预配置的 API Key 列表 */
    private List<ApiKeyEntry> apiKeys = new ArrayList<>();

    /** CORS 配置 */
    private CorsConfig cors = new CorsConfig();

    /** API Key 到条目的 O(1) 查找表（内部缓存，不对外暴露 getter/setter） */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Map<String, ApiKeyEntry> lookup;

    @PostConstruct
    public void init() {
        lookup = new HashMap<>();
        if (apiKeys != null) {
            for (ApiKeyEntry entry : apiKeys) {
                if (entry.getKey() != null) {
                    lookup.put(entry.getKey(), entry);
                }
            }
        }
    }

    /**
     * 根据 API Key 查找对应的用户条目
     * @param apiKey API Key 字符串
     * @return 匹配的 ApiKeyEntry，未找到返回 null
     */
    public ApiKeyEntry lookup(String apiKey) {
        return lookup != null ? lookup.get(apiKey) : null;
    }

    // ===== 嵌套配置类 =====

    /**
     * API Key 条目：key / userId / description
     */
    @Getter
    @Setter
    public static class ApiKeyEntry {
        private String key;
        private String userId;
        private String description;
    }

    /**
     * CORS 配置子项
     */
    @Getter
    @Setter
    public static class CorsConfig {
        private boolean allowCredentials = false;
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedMethods = new ArrayList<>();
        private List<String> allowedHeaders = new ArrayList<>();
        private long maxAge = 3600;
    }
}
