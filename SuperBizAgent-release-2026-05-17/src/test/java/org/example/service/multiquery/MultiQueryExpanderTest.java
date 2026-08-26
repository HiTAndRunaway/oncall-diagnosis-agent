package org.example.service.multiquery;

import org.example.config.MultiQueryProperties;
import org.example.service.DashScopeLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MultiQueryExpander 单元测试（mock LLM 与 Redis，不依赖真实服务）
 * <p>
 * 验证：变体解析、数量截断、缓存命中、全有或全无降级（解析失败/LLM 异常/空查询 → 空列表）。
 */
class MultiQueryExpanderTest {

    private DashScopeLlmClient llmClient;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MultiQueryProperties properties;
    private MultiQueryExpander expander;

    @BeforeEach
    void setUp() {
        llmClient = mock(DashScopeLlmClient.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        properties = new MultiQueryProperties();
        expander = new MultiQueryExpander(llmClient, properties, redisTemplate);
    }

    @Test
    void shouldParseVariantsFromLlmResponse() {
        properties.setMaxVariants(5);
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("""
                [{"query": "接口延迟高如何定位", "angle": "KEYWORD", "rationale": "关键词角度"},
                 {"query": "线上接口变慢排查步骤", "angle": "CAUSE_STEP", "rationale": "步骤角度"},
                 {"query": "正常与异常响应对比", "angle": "COMPARE", "rationale": "对比角度"}]
                """);

        List<QueryVariant> variants = expander.expand("服务响应慢怎么排查");

        assertEquals(3, variants.size());
        assertEquals("接口延迟高如何定位", variants.get(0).query());
        assertEquals("KEYWORD", variants.get(0).angle());
        assertEquals(1, variants.get(0).index());
        assertEquals("COMPARE", variants.get(2).angle());
        assertEquals(3, variants.get(2).index());
    }

    @Test
    void shouldTruncateToMaxVariants() {
        properties.setMaxVariants(2);
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("""
                [{"query": "q1", "angle": "KEYWORD"}, {"query": "q2", "angle": "SCENE"}, {"query": "q3", "angle": "SUB_QUESTION"}]
                """);

        List<QueryVariant> variants = expander.expand("问题");

        assertEquals(2, variants.size());
        assertEquals("q1", variants.get(0).query());
        assertEquals("q2", variants.get(1).query());
    }

    @Test
    void shouldReturnEmptyOnParseFailure() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("抱歉，我无法理解这个问题");

        assertTrue(expander.expand("问题").isEmpty());
    }

    @Test
    void shouldReturnEmptyOnLlmException() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("LLM timeout"));

        assertTrue(expander.expand("问题").isEmpty());
    }

    @Test
    void shouldReturnEmptyOnEmptyQuery() {
        assertTrue(expander.expand("").isEmpty());
        assertTrue(expander.expand(null).isEmpty());
    }

    @Test
    void shouldHitCacheWithoutCallingLlm() {
        when(valueOps.get(anyString())).thenReturn(
                "[{\"index\":1,\"query\":\"q1\",\"angle\":\"KEYWORD\",\"rationale\":\"r1\"}]");

        List<QueryVariant> variants = expander.expand("问题");

        assertEquals(1, variants.size());
        assertEquals("q1", variants.get(0).query());
        verify(llmClient, never()).call(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void shouldSupportObjectWrappedVariants() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"variants\": [{\"query\": \"q1\", \"angle\": \"SCENE\"}]}");

        List<QueryVariant> variants = expander.expand("问题");

        assertEquals(1, variants.size());
        assertEquals("q1", variants.get(0).query());
        assertEquals("SCENE", variants.get(0).angle());
    }

    @Test
    void shouldParseVariantsFromMarkdownCodeBlock() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("""
                ```json
                [{"query": "q1", "angle": "KEYWORD"}]
                ```
                """);

        List<QueryVariant> variants = expander.expand("问题");

        assertEquals(1, variants.size());
        assertEquals("q1", variants.get(0).query());
    }

    @Test
    void shouldReturnEmptyWhenLlmReturnsEmptyArray() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("[]");

        assertTrue(expander.expand("问题").isEmpty());
    }

    @Test
    void shouldSkipVariantsWithEmptyQuery() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("""
                [{"query": "", "angle": "KEYWORD"}, {"query": "有效变体", "angle": "SCENE"}, {"query": "  ", "angle": "COMPARE"}]
                """);

        List<QueryVariant> variants = expander.expand("问题");

        assertEquals(1, variants.size());
        assertEquals("有效变体", variants.get(0).query());
    }
}
