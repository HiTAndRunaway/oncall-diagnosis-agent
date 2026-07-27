package org.example.config;

import org.example.security.ApiKeyAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 * 安全开关 disabled 时放行所有请求；enabled 时启用 API Key 认证 + 白名单 + 401/403 JSON 响应
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    /** 无需认证的路径白名单 */
    private static final String[] WHITELIST = {
            "/api/login", "/login.html", "/login.js", "/login.css",
            "/actuator/health", "/milvus/health", "/favicon.ico"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 安全开关关闭：放行所有请求，禁用 CSRF
        if (!apiKeyProperties.isEnabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            http.csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }

        // 安全开关开启：启用 API Key 认证
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(WHITELIST).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeyAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":401,\"message\":\"Unauthorized\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":403,\"message\":\"Forbidden\"}");
                        }));

        return http.build();
    }
}
