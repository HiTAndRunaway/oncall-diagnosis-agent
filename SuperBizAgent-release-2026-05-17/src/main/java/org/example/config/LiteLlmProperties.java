package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * liteLLM 网关配置属性
 * <p>
 * enabled=false（默认）时系统保持现有 DashScope 直连行为；
 * enabled=true 时所有模型调用走 liteLLM OpenAI 兼容网关。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "litellm")
public class LiteLlmProperties {

    /** 网关总开关：true=走 liteLLM，false=DashScope 直连（默认） */
    private boolean enabled = false;

    /** liteLLM 网关地址，如 http://localhost:4000 */
    @Setter(AccessLevel.NONE)
    private String baseUrl = "http://localhost:4000";

    /** liteLLM 虚拟密钥（按业务域分配的 key，需先在网关创建） */
    private String apiKey = "";

    /** 默认占位符值（未配置真实 key 时的提示） */
    private static final String PLACEHOLDER_API_KEY = "sk-litellm-change-me";

    /**
     * 启动校验：网关开启时必须具备可达地址与有效虚拟密钥，避免首次调用才暴露 401/连接异常。
     * <p>
     * 注意：取值限定为布尔语义（true/false）；其他值（yes/1/on）会导致两个 LlmProvider
     * 条件注册都不匹配而启动失败，请使用标准布尔值。
     */
    @PostConstruct
    public void validate() {
        if (enabled) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException(
                        "litellm.enabled=true 但 litellm.base-url 未配置，请设置 liteLLM 网关地址");
            }
            if (apiKey == null || apiKey.isBlank() || PLACEHOLDER_API_KEY.equals(apiKey)) {
                throw new IllegalStateException(
                        "litellm.enabled=true 但 litellm.api-key 未配置或仍为占位符，请在 liteLLM 网关创建虚拟密钥并设置环境变量 LITELLM_API_KEY");
            }
        }
    }

    /**
     * 规范化 base-url：去除尾部斜杠，避免拼接端点时出现 "//v1/..."。
     * （自定义 setter，Lombok 已对该字段关闭自动生成）
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }
}
