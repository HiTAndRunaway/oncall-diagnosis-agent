package org.example.config;

import jakarta.annotation.PostConstruct;
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

    /** API Key 到条目的 O(1) 查找表 */
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

    // ===== Getters & Setters =====

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public List<ApiKeyEntry> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKeyEntry> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public CorsConfig getCors() {
        return cors;
    }

    public void setCors(CorsConfig cors) {
        this.cors = cors;
    }

    // ===== 嵌套配置类 =====

    /**
     * API Key 条目：key / userId / description
     */
    public static class ApiKeyEntry {
        private String key;
        private String userId;
        private String description;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    /**
     * CORS 配置子项
     */
    public static class CorsConfig {
        private boolean allowCredentials = false;
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedMethods = new ArrayList<>();
        private List<String> allowedHeaders = new ArrayList<>();
        private long maxAge = 3600;

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public List<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public long getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(long maxAge) {
            this.maxAge = maxAge;
        }
    }
}
