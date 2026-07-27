package org.example.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * API Key 认证令牌
 * getName() 返回 userId，作为控制器获取当前用户的统一入口
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String userId;
    private final String apiKey;

    /**
     * 未认证令牌（仅含 API Key）
     */
    public ApiKeyAuthenticationToken(String apiKey) {
        super(null);
        this.apiKey = apiKey;
        this.userId = null;
        setAuthenticated(false);
    }

    /**
     * 已认证令牌（含 userId + 权限）
     */
    public ApiKeyAuthenticationToken(String userId, String apiKey,
                                      Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.apiKey = apiKey;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    /**
     * 关键设计：getName() 返回 userId
     * 控制器通过 SecurityContextHolder.getContext().getAuthentication().getName() 获取当前用户
     */
    @Override
    public String getName() {
        return userId;
    }

    public String getApiKey() {
        return apiKey;
    }
}
