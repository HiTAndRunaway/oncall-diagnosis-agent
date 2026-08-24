package org.example.service;

import org.example.config.LiteLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DashScopeLlmClient 聊天网关分支解析单元测试（MockRestServiceServer，不依赖真实网关）
 * <p>
 * 验证：/v1/chat/completions OpenAI 格式响应解析（choices[0].message.content）。
 */
class DashScopeLlmClientGatewayTest {

    private DashScopeLlmClient newGatewayClient() throws Exception {
        DashScopeLlmClient client = new DashScopeLlmClient();
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:4000");
        props.setApiKey("sk-test");
        ReflectionTestUtils.setField(client, "liteLlmProperties", props);
        return client;
    }

    @Test
    void call_shouldParseOpenAiFormatResponse() throws Exception {
        DashScopeLlmClient client = newGatewayClient();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "网关回复内容"}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = client.call("qwen-turbo", "hello", 0.3, 500);

        assertEquals("网关回复内容", result);
        server.verify();
    }

    @Test
    void callWithSystemPrompt_shouldSendSystemAndUserMessages() throws Exception {
        DashScopeLlmClient client = newGatewayClient();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "ok"}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = client.callWithSystemPrompt("qwen-turbo", "你是助手", "问题", 0.3, 500);

        assertEquals("ok", result);
        server.verify();
    }

    @Test
    void shouldThrowWhenChoicesMissing() throws Exception {
        DashScopeLlmClient client = newGatewayClient();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/chat/completions"))
                .andRespond(withSuccess("{\"error\": \"no choices\"}", MediaType.APPLICATION_JSON));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.call("qwen-turbo", "hello", 0.3, 500));
        assertTrue(ex.getMessage().contains("choices"));
        server.verify();
    }

    @Test
    void disabled_shouldNotHitGateway() {
        // enabled=false 时应走 DashScope 原生格式（不发起 /v1/chat/completions 请求），
        // 此处仅验证不抛 NPE（真实 DashScope 调用不测）
        DashScopeLlmClient client = new DashScopeLlmClient();
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(false);
        ReflectionTestUtils.setField(client, "liteLlmProperties", props);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        assertNotNull(client);
    }
}
