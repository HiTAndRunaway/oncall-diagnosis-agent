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
 * liteLLM 默认模式（enabled=false）上下文冒烟测试
 * <p>
 * 验证默认配置下：DashScopeLlmProvider 注册、LiteLlmProvider 不注册（二选一生效），
 * 与改造前行为一致（无行为漂移）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "litellm.enabled=false",
    "milvus.host=",
    "spring.data.redis.host=",
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class LiteLlmDefaultModeContextTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private MilvusServiceClient milvusServiceClient;

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void context_shouldLoadInDefaultMode() {
        assertNotNull(context);
    }

    @Test
    void dashScopeLlmProvider_shouldBeRegistered() {
        assertTrue(context.containsBean("dashScopeLlmProvider"),
                "litellm.enabled=false（默认）时应注册 DashScopeLlmProvider");
        assertNotNull(context.getBean(DashScopeLlmProvider.class));
    }

    @Test
    void liteLlmProvider_shouldNotBeRegistered() {
        assertFalse(context.containsBean("liteLlmProvider"),
                "litellm.enabled=false 时 LiteLlmProvider 不应注册（二选一）");
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                () -> context.getBean(LiteLlmProvider.class));
    }
}
