package org.example.security;

import org.example.config.ApiKeyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * API Key 认证管理器
 * 在 ApiKeyProperties 的 lookup 表中查找 API Key，验证通过后返回已认证令牌
 */
@Component
public class ApiKeyAuthManager implements AuthenticationManager {

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String apiKey = (String) authentication.getCredentials();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new BadCredentialsException("API Key is required");
        }

        ApiKeyProperties.ApiKeyEntry entry = apiKeyProperties.lookup(apiKey);
        if (entry == null) {
            throw new BadCredentialsException("Invalid API Key");
        }

        List<GrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_USER"));

        return new ApiKeyAuthenticationToken(entry.getUserId(), apiKey, authorities);
    }
}
