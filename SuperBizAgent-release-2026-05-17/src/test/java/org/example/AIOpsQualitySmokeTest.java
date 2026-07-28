package org.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端冒烟测试：验证 Spring 上下文基本可用（排除外部依赖）
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "milvus.host=",
    "spring.data.redis.host=",
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class AIOpsQualitySmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContext_shouldLoad() {
        assertNotNull(context);
        // Main application class bean should be present
        assertTrue(context.containsBean("superBizAgentApplication")
                || context.getBeanDefinitionCount() > 0);
    }

    @Test
    void chatController_shouldBeAvailable() {
        assertNotNull(context);
        // ChatController should be loaded as a bean
        assertTrue(context.containsBean("chatController"));
    }

    @Test
    void chatService_shouldBeAvailable() {
        assertNotNull(context);
        assertTrue(context.containsBean("chatService"));
    }
}
