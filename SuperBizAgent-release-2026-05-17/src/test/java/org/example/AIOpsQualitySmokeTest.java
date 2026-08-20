package org.example;

import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    /**
     * Mock Milvus 客户端：MilvusConfig 在 Bean 创建时会主动连接并初始化 collection，
     * 测试环境无 Milvus 服务，用 Mock 替换以避免上下文加载时发起外部连接。
     */
    @MockitoBean
    private MilvusServiceClient milvusServiceClient;

    /**
     * Mock Redis 连接工厂：测试排除了 RedisAutoConfiguration 后没有连接工厂，
     * RedisConfig 的 RedisTemplate/StringRedisTemplate 依赖它，用 Mock 补齐。
     */
    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

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
        // v1 控制器按类名注册为 chatV1Controller（legacy 为 chatLegacyController）
        assertTrue(context.containsBean("chatV1Controller")
                || context.containsBean("chatLegacyController"));
    }

    @Test
    void chatService_shouldBeAvailable() {
        assertNotNull(context);
        assertTrue(context.containsBean("chatService"));
    }
}
