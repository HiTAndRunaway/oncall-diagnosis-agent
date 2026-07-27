package org.example.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 基于 ApiKeyProperties 的 CORS 配置
 * 仅在安全开关开启且配置了 allowedOrigins 时生效
 * 安全关闭时由 WebMvcConfig 处理允许所有来源的 CORS
 */
@Configuration
public class SecurityCorsConfig implements WebMvcConfigurer {

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!apiKeyProperties.isEnabled()) {
            return;
        }

        ApiKeyProperties.CorsConfig cors = apiKeyProperties.getCors();
        if (cors == null || cors.getAllowedOrigins().isEmpty()) {
            return;
        }

        registry.addMapping("/**")
                .allowedOrigins(cors.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods(cors.getAllowedMethods().toArray(new String[0]))
                .allowedHeaders(cors.getAllowedHeaders().toArray(new String[0]))
                .allowCredentials(cors.isAllowCredentials())
                .maxAge(cors.getMaxAge());
    }
}
