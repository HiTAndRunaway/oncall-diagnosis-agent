package org.example;

import io.milvus.client.MilvusServiceClient;
import org.example.agent.DashScopeLlmProvider;
import org.example.agent.LiteLlmProvider;
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
 * liteLLM 网关模式上下文冒烟测试
 * <p>
 * 验证 {@code litellm.enabled=true} 时：
 * 1. Spring 上下文可正常加载（排除外部依赖）
 * 2. LiteLlmProvider 注册、DashScopeLlmProvider 不注册（二选一生效）
 * 3. VectorEmbeddingService 在网关模式下跳过 DashScope SDK 初始化，启动不要求 DASHSCOPE_API_KEY
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "litellm.enabled=true",
    "litellm.base-url=http://localhost:4000",
    "litellm.api-key=sk-test",
    "milvus.host=",
    "spring.data.redis.host=",
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class LiteLlmGatewayContextTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private MilvusServiceClient milvusServiceClient;

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void context_shouldLoadInGatewayMode() {
        assertNotNull(context);
    }

    @Test
    void liteLlmProvider_shouldBeRegistered() {
        assertTrue(context.containsBean("liteLlmProvider"),
                "litellm.enabled=true 时应注册 LiteLlmProvider");
        assertNotNull(context.getBean(LiteLlmProvider.class));
    }

    @Test
    void dashScopeLlmProvider_shouldNotBeRegistered() {
        assertFalse(context.containsBean("dashScopeLlmProvider"),
                "litellm.enabled=true 时 DashScopeLlmProvider 不应注册（二选一）");
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(DashScopeLlmProvider.class));
    }
}
