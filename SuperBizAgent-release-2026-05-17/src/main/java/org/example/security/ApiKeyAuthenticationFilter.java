package org.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.config.ApiKeyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API Key 认证过滤器
 * 从请求头中提取 API Key，委托 ApiKeyAuthManager 进行认证
 * 安全开关关闭时直接放行
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    @Autowired
    private ApiKeyAuthManager apiKeyAuthManager;

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        // 安全开关关闭时直接放行
        if (!apiKeyProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String headerName = apiKeyProperties.getApiKeyHeader();
        String apiKey = request.getHeader(headerName);

        if (apiKey == null || apiKey.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            ApiKeyAuthenticationToken authRequest = new ApiKeyAuthenticationToken(apiKey);
            Authentication authResult = apiKeyAuthManager.authenticate(authRequest);
            SecurityContextHolder.getContext().setAuthentication(authResult);
            logger.debug("API Key authenticated for user: {}", authResult.getName());
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            logger.warn("API Key authentication failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
