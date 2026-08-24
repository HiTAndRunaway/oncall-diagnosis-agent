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
 * VectorSearchService rerank 网关分支解析单元测试（MockRestServiceServer，不依赖真实网关）
 * <p>
 * 验证：/v1/rerank 请求格式、顶层 results 与 DashScope 风格 output.results 两种响应形态的兼容解析。
 */
class VectorSearchServiceGatewayTest {

    private VectorSearchService newGatewayService() throws Exception {
        VectorSearchService service = new VectorSearchService();
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:4000");
        props.setApiKey("sk-test");
        ReflectionTestUtils.setField(service, "liteLlmProperties", props);
        ReflectionTestUtils.setField(service, "rerankModel", "gte-rerank-v2");
        ReflectionTestUtils.setField(service, "rerankTopK", 10);
        return service;
    }

    /**
     * 反射调用 callRerankApiGateway 并返回 RerankResultItem 列表（私有类，用反射读取字段）。
     */
    private List<?> invokeAndGetItems(VectorSearchService service) throws Exception {
        Method m = VectorSearchService.class.getDeclaredMethod("callRerankApiGateway", String.class, List.class);
        m.setAccessible(true);
        Object result = m.invoke(service, "query", List.of("docA", "docB"));
        Object output = result.getClass().getMethod("getOutput").invoke(result);
        return (List<?>) output.getClass().getMethod("getResults").invoke(output);
    }

    private static int indexOf(Object item) throws Exception {
        return (int) item.getClass().getMethod("getIndex").invoke(item);
    }

    private static double scoreOf(Object item) throws Exception {
        return (double) item.getClass().getMethod("getRelevanceScore").invoke(item);
    }

    @Test
    void shouldParseTopLevelResults() throws Exception {
        VectorSearchService service = newGatewayService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/rerank"))
                .andRespond(withSuccess("""
                        {"results": [{"index": 1, "relevance_score": 0.9}, {"index": 0, "relevance_score": 0.5}]}
                        """, MediaType.APPLICATION_JSON));

        List<?> items = invokeAndGetItems(service);

        assertEquals(2, items.size());
        assertEquals(1, indexOf(items.get(0)));
        assertEquals(0.9, scoreOf(items.get(0)));
        assertEquals(0, indexOf(items.get(1)));
        assertEquals(0.5, scoreOf(items.get(1)));
        server.verify();
    }

    @Test
    void shouldParseDashScopeStyleOutputResults() throws Exception {
        VectorSearchService service = newGatewayService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/rerank"))
                .andRespond(withSuccess("""
                        {"output": {"results": [{"index": 0, "relevance_score": 0.8}]}}
                        """, MediaType.APPLICATION_JSON));

        List<?> items = invokeAndGetItems(service);

        assertEquals(1, items.size());
        assertEquals(0, indexOf(items.get(0)));
        assertEquals(0.8, scoreOf(items.get(0)));
        server.verify();
    }

    @Test
    void shouldParseStringNumericFields() throws Exception {
        VectorSearchService service = newGatewayService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/rerank"))
                .andRespond(withSuccess("""
                        {"results": [{"index": "2", "relevance_score": "0.95"}]}
                        """, MediaType.APPLICATION_JSON));

        List<?> items = invokeAndGetItems(service);

        assertEquals(2, indexOf(items.get(0)));
        assertEquals(0.95, scoreOf(items.get(0)));
        server.verify();
    }

    @Test
    void shouldThrowOnUnparseableField() throws Exception {
        VectorSearchService service = newGatewayService();
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        server.expect(requestTo("http://localhost:4000/v1/rerank"))
                .andRespond(withSuccess("""
                        {"results": [{"index": "abc", "relevance_score": 0.5}]}
                        """, MediaType.APPLICATION_JSON));

        Method m = VectorSearchService.class.getDeclaredMethod("callRerankApiGateway", String.class, List.class);
        m.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> m.invoke(service, "query", List.of("docA")));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "解析失败应抛出 IllegalArgumentException（上层 rerank() 降级为原始排序）");
        server.verify();
    }
}
