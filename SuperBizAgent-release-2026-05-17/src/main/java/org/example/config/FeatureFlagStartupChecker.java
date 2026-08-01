package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlagStartupChecker {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagStartupChecker.class);

    @Value("${memory.enabled:true}")
    private boolean memoryEnabled;

    @Value("${rag.agentic.enabled:false}")
    private boolean agenticRagEnabled;

    @Value("${superbiz.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${superbiz.rate-limit.enabled:false}")
    private boolean rateLimitEnabled;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (memoryEnabled && redisTemplate == null) {
            log.warn("[FeatureFlag] memory.enabled=true 但 Redis 不可用，记忆功能可能异常");
        }
        if (agenticRagEnabled) {
            log.info("[FeatureFlag] rag.agentic.enabled=true，请确保 biz collection 中有数据");
        }
        if (rateLimitEnabled && !securityEnabled) {
            log.warn("[FeatureFlag] rate-limit.enabled=true 但 security.enabled=false，"
                    + "限流依赖用户身份识别，建议同时开启安全认证");
        }
    }
}
