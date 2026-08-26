package org.example.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * DashScope API Key 统一配置
 * 业务代码统一通过此类注入，不再散落 @Value("${dashscope.api.key}")
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "dashscope.api")
@Validated
public class DashScopeApiProperties {

    @NotBlank(message = "DashScope API Key 不能为空")
    private String key;
}
