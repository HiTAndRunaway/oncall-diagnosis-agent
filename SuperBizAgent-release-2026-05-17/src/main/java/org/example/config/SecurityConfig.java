package org.example.config;

import org.example.security.ApiKeyAuthManager;
import org.example.security.ApiKeyAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security 配置
 * 安全开关 disabled 时放行所有请求；enabled 时启用 API Key 认证 + 白名单 + 401/403 JSON 响应
 * <p>
 * <b>注意：本类整体始终注册，但两条过滤器链按开关二选一。</b>
 * 早期实现把整个类设为 {@code @ConditionalOnProperty(enabled=true)}，于是关闭状态下
 * 容器里没有任何 {@code SecurityFilterChain}，Spring Boot 便自动装配其<b>默认安全链</b>
 * （{@code anyRequest().authenticated()} + 表单登录），所有业务端点被 302 重定向到默认
 * 登录页 —— 与「放行所有请求」的语义完全相反。此缺陷由
 * {@code SecurityFilterChainStartupTest} 的启动验证发现。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    /** 无需认证的路径白名单 */
    private static final String[] WHITELIST = {
            "/api/login", "/login.html", "/login.js", "/login.css",
            "/actuator/health", "/milvus/health", "/favicon.ico"
    };

    /**
     * 安全开关关闭时的过滤器链：放行所有请求
     * <p>
     * 必须显式注册，否则 Spring Boot 的默认安全链会接管并拦截全部请求。
     */
    @Bean
    @ConditionalOnProperty(prefix = "superbiz.security", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain permitAllSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * 安全开关开启时的过滤器链：API Key 认证
     */
    @Bean
    @ConditionalOnProperty(prefix = "superbiz.security", name = "enabled", havingValue = "true")
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter)
            throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        ApiKeyProperties.CorsConfig corsConfig = apiKeyProperties.getCors();
        if (corsConfig != null) {
            config.setAllowedOrigins(corsConfig.getAllowedOrigins());
            config.setAllowedMethods(corsConfig.getAllowedMethods());
            config.setAllowedHeaders(corsConfig.getAllowedHeaders());
            config.setAllowCredentials(corsConfig.isAllowCredentials());
            config.setMaxAge(corsConfig.getMaxAge());
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyAuthManager apiKeyAuthManager,
                                                                  ApiKeyProperties apiKeyProperties) {
        return new ApiKeyAuthenticationFilter(apiKeyAuthManager, apiKeyProperties);
    }
}
