package org.example.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.config.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Bucket4j 的请求限流拦截器
 * 使用 Caffeine 缓存存储每个 userId:path 的令牌桶，15 分钟未访问自动过期
 * 超限时返回 429 JSON 响应
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** userId:path -> Bucket，15 分钟未访问自动过期 */
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder()
            .expireAfterAccess(15, TimeUnit.MINUTES)
            .build();

    @Autowired
    private RateLimitConfig rateLimitConfig;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!rateLimitConfig.isEnabled()) {
            return true;
        }

        // 从 SecurityContext 获取当前用户，未认证则标记为 anonymous
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
        String path = request.getRequestURI();

        String cacheKey = userId + ":" + path;
        Bucket bucket = bucketCache.get(cacheKey, k -> createBucket(path));

        if (bucket.tryConsume(1)) {
            return true;
        }

        logger.warn("Rate limit exceeded for user: {}, path: {}", userId, path);
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"Too Many Requests\"}");
        return false;
    }

    /**
     * 根据路径创建令牌桶，优先使用端点的自定义配置，否则使用默认配置
     */
    private Bucket createBucket(String path) {
        RateLimitConfig.EndpointLimit limit = rateLimitConfig.getEndpointLimit(path);
        int capacity = (limit != null) ? limit.getCapacity() : rateLimitConfig.getDefaultCapacity();
        int refillRate = (limit != null) ? limit.getRefillRate() : rateLimitConfig.getDefaultRefillRate();

        Bandwidth bandwidth = Bandwidth.classic(capacity,
                Refill.greedy(refillRate, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
