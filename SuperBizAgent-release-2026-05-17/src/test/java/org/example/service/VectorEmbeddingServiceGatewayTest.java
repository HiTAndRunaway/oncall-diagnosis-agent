package org.example.service;

import org.example.config.LiteLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * VectorEmbeddingService Embedding 网关分支解析单元测试（MockRestServiceServer，不依赖真实网关）
 * <p>
 * 验证：/v1/embeddings 请求、data[] 响应解析、缺失 embedding 字段的防护。
 */
class VectorEmbeddingServiceGatewayTest {

    private VectorEmbeddingService newGatewayService() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:4000");
        props.setApiKey("sk-test");
        ReflectionTestUtils.setField(service, "liteLlmProperties", props);
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        return service;
    }

    @SuppressWarnings("unchecked")
    private List<List<Float>> invokeGateway(VectorEmbeddingService service, List<String> texts) throws Exception {
        Method m = VectorEmbeddingService.class.getDeclaredMethod("generateEmbeddingsViaGateway", List.class);
        m.setAccessible(true);
        return (List<List<Float>>) m.invoke(service, texts);
    }

    @Test
    void shouldParseEmbeddingData() throws Exception {
        VectorEmbeddingService service = newGatewayService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data": [
                          {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                          {"index": 1, "embedding": [0.4, 0.5]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<List<Float>> embeddings = invokeGateway(service, List.of("docA", "docB"));

        assertEquals(2, embeddings.size());
        assertEquals(List.of(0.1f, 0.2f, 0.3f), embeddings.get(0));
        assertEquals(List.of(0.4f, 0.5f), embeddings.get(1));
        server.verify();
    }

    @Test
    void shouldThrowWhenEmbeddingFieldMissing() throws Exception {
        VectorEmbeddingService service = newGatewayService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data": [{"index": 0}]}
                        """, MediaType.APPLICATION_JSON));

        Method m = VectorEmbeddingService.class.getDeclaredMethod("generateEmbeddingsViaGateway", List.class);
        m.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> m.invoke(service, List.of("docA")));
        assertInstanceOf(RuntimeException.class, ex.getCause());
        server.verify();
    }
}
