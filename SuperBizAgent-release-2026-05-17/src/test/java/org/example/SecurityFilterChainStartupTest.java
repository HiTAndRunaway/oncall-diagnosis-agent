package org.example;

import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全链路的<b>真实 HTTP 启动验证</b>（随机端口，走完整 Servlet 过滤器链）
 * <p>
 * 背景：{@code SecurityConfig} 整个类标注了
 * {@code @ConditionalOnProperty(superbiz.security.enabled=true)}，当该开关为
 * {@code false}（默认）时<b>不注册任何自定义 {@code SecurityFilterChain}</b>。
 * 这带来一个此前未被验证的问题：Spring Boot 的<b>默认安全链</b>
 * （{@code anyRequest().authenticated()} + basic/form 登录）是否会接管并拦截所有请求，
 * 从而推翻 {@code application.yml} 中「false=放行所有请求」的注释说明。
 * <p>
 * 本测试用真实 HTTP 调用回答该问题，并锁定以下行为：
 * <ol>
 *   <li>安全开关关闭时，非白名单业务端点仍可访问（未被默认链拦截）</li>
 *   <li>未认证调用记忆删除接口返回 401（而非 500/503/302 跳转登录页）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "litellm.enabled=false",
    "milvus.host=",
    "spring.data.redis.host=",
    "superbiz.security.enabled=false",
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class SecurityFilterChainStartupTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private MilvusServiceClient milvusServiceClient;

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * 安全开关关闭时的实际过滤器链行为：业务端点不得被默认安全链拦截。
     * <p>
     * 若此断言失败（例如返回 401 或 302 跳转登录页），说明需要为
     * {@code security.enabled=false} 提供一个显式的 permitAll 链 ——
     * 这正是本次要验证的「配置注释与运行时是否一致」。
     */
    @Test
    void securityDisabled_businessEndpoint_isNotBlockedByDefaultChain() {
        // /milvus/health 在两种链下都属白名单/无鉴权，先确认服务器正常响应
        ResponseEntity<String> health = rest.getForEntity(url("/milvus/health"), String.class);
        assertTrue(health.getStatusCode().is2xxSuccessful() || health.getStatusCode().is5xxServerError(),
                "健康检查端点应能被访问到（2xx 或依赖不可用的 5xx），实际: " + health.getStatusCode());

        // /api/v1/memory/panel 是非白名单业务端点：安全关闭时应可到达控制器
        ResponseEntity<String> panel = rest.getForEntity(url("/api/v1/memory/panel"), String.class);
        HttpStatus status = HttpStatus.resolve(panel.getStatusCode().value());

        assertNotEquals(HttpStatus.UNAUTHORIZED, status,
                "security.enabled=false 时不应返回 401 —— 说明 Spring Boot 默认安全链接管了请求");
        assertNotEquals(HttpStatus.FORBIDDEN, status,
                "security.enabled=false 时不应返回 403");
        assertFalse(status != null && status.is3xxRedirection(),
                "security.enabled=false 时不应被重定向到登录页，实际: " + status);
    }

    /**
     * 变更类端点：未认证调用必须得到 401，而不是落到兜底 500 或被吞成 503。
     * <p>
     * 这验证 {@code GlobalExceptionHandler} 对 {@code IllegalStateException} 的
     * 401 映射，以及 {@code MemoryV1Controller} 中 {@code catch (BizException)}
     * 重抛、避免把 401 转成 503 的修复。
     */
    @Test
    void memoryDelete_withoutAuthentication_returns401() {
        ResponseEntity<String> delete =
                rest.exchange(url("/api/v1/memory/some-memory-id"),
                        org.springframework.http.HttpMethod.DELETE, null, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, HttpStatus.resolve(delete.getStatusCode().value()),
                "未认证删除记忆应返回 401，实际: " + delete.getStatusCode() + " body=" + delete.getBody());
        assertNotNull(delete.getBody());
        assertTrue(delete.getBody().contains("401"), "响应体应含 401 语义，实际: " + delete.getBody());
    }

    @Test
    void memoryClear_withoutAuthentication_returns401() {
        ResponseEntity<String> clear =
                rest.exchange(url("/api/v1/memory/clear"),
                        org.springframework.http.HttpMethod.DELETE, null, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, HttpStatus.resolve(clear.getStatusCode().value()),
                "未认证清空记忆应返回 401，实际: " + clear.getStatusCode() + " body=" + clear.getBody());
    }

    /**
     * 记录实际生效的安全行为，便于后续排查（不参与断言逻辑的强约束）。
     */
    @Test
    void recordObservedSecurityBehaviour() {
        ResponseEntity<Map> root = rest.getForEntity(url("/api/v1/memory/panel"), Map.class);
        System.out.println("[SecurityFilterChainStartupTest] security.enabled=false 下 "
                + "GET /api/v1/memory/panel → " + root.getStatusCode());
    }
}
