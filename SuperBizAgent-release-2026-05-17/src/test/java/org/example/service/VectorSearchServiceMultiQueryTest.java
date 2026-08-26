package org.example.service;

import org.example.service.multiquery.MultiQueryExpander;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VectorSearchService 多角度查询路（Road C）单元测试
 * <p>
 * 验证：
 * 1. rrfFusion 多路通用版：三路融合排序、权重影响、空路兼容、同 id 去重；
 * 2. multiQuerySearch 全有或全无：变体为空/异常 → 整路返回空列表（降级两路）。
 */
class VectorSearchServiceMultiQueryTest {

    // === rrfFusion 多路融合 ===

    private List<VectorSearchService.SearchResult> invokeRrfFusion(
            List<List<VectorSearchService.SearchResult>> lists,
            List<Double> weights, int k, int topK) throws Exception {
        VectorSearchService service = new VectorSearchService();
        Method m = VectorSearchService.class.getDeclaredMethod(
                "rrfFusion", List.class, List.class, int.class, int.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<VectorSearchService.SearchResult> result =
                (List<VectorSearchService.SearchResult>) m.invoke(service, lists, weights, k, topK);
        return result;
    }

    private VectorSearchService.SearchResult result(String id) {
        VectorSearchService.SearchResult r = new VectorSearchService.SearchResult();
        r.setId(id);
        r.setContent("content-" + id);
        return r;
    }

    @Test
    void shouldFuseThreeRoadsWithEqualWeights() throws Exception {
        // dense:  A(rank1), B(rank2)
        // sparse: B(rank1), C(rank2)
        // multi:  C(rank1), D(rank2)
        List<VectorSearchService.SearchResult> dense = List.of(result("A"), result("B"));
        List<VectorSearchService.SearchResult> sparse = List.of(result("B"), result("C"));
        List<VectorSearchService.SearchResult> multi = List.of(result("C"), result("D"));

        List<VectorSearchService.SearchResult> fused = invokeRrfFusion(
                List.of(dense, sparse, multi), List.of(1.0, 1.0, 1.0), 60, 10);

        // B: 1/61+1/61=0.0328, C: 1/62+1/61=0.0325, A: 1/61=0.0164, D: 1/62=0.0161
        assertEquals(4, fused.size());
        assertEquals("B", fused.get(0).getId());
        assertEquals("C", fused.get(1).getId());
    }

    @Test
    void shouldSkipEmptyRoad() throws Exception {
        List<VectorSearchService.SearchResult> dense = List.of(result("A"));
        List<VectorSearchService.SearchResult> multi = List.of(result("B"));

        // 中间一路为空（模拟 BM25 路失败降级）
        List<VectorSearchService.SearchResult> fused = invokeRrfFusion(
                List.of(dense, List.of(), multi), List.of(1.0, 1.0, 1.0), 60, 10);

        assertEquals(2, fused.size());
        assertEquals("A", fused.get(0).getId());
        assertEquals("B", fused.get(1).getId());
    }

    @Test
    void shouldDeduplicateSameIdAcrossRoads() throws Exception {
        List<VectorSearchService.SearchResult> dense = List.of(result("X"), result("Y"));
        List<VectorSearchService.SearchResult> multi = List.of(result("X"));

        List<VectorSearchService.SearchResult> fused = invokeRrfFusion(
                List.of(dense, multi), List.of(1.0, 1.0), 60, 10);

        // X 只出现一次，且 X(2/61) 排在 Y(1/62) 之前
        assertEquals(2, fused.size());
        assertEquals("X", fused.get(0).getId());
    }

    @Test
    void shouldHonorWeightDifferences() throws Exception {
        List<VectorSearchService.SearchResult> dense = List.of(result("A"));
        List<VectorSearchService.SearchResult> multi = List.of(result("B"));

        // multiQuery 权重 2.0：B(2/61) > A(1/61)
        List<VectorSearchService.SearchResult> fused = invokeRrfFusion(
                List.of(dense, multi), List.of(1.0, 2.0), 60, 10);

        assertEquals("B", fused.get(0).getId());
    }

    // === multiQuerySearch 全有或全无 ===

    private List<VectorSearchService.SearchResult> invokeMultiQuerySearch(
            VectorSearchService service, String query) throws Exception {
        Method m = VectorSearchService.class.getDeclaredMethod("multiQuerySearch", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<VectorSearchService.SearchResult> result =
                (List<VectorSearchService.SearchResult>) m.invoke(service, query);
        return result;
    }

    @Test
    void shouldAbandonRoadWhenExpanderReturnsEmpty() throws Exception {
        VectorSearchService service = new VectorSearchService();
        MultiQueryExpander expander = mock(MultiQueryExpander.class);
        when(expander.expand(anyString())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "multiQueryExpander", expander);
        ReflectionTestUtils.setField(service, "multiQueryEnabled", true);

        List<VectorSearchService.SearchResult> result = invokeMultiQuerySearch(service, "query");

        assertTrue(result.isEmpty(), "变体为空时应整路放弃，返回空列表（降级两路）");
    }

    @Test
    void shouldAbandonRoadWhenExpanderThrows() throws Exception {
        VectorSearchService service = new VectorSearchService();
        MultiQueryExpander expander = mock(MultiQueryExpander.class);
        when(expander.expand(anyString())).thenThrow(new RuntimeException("boom"));
        ReflectionTestUtils.setField(service, "multiQueryExpander", expander);
        ReflectionTestUtils.setField(service, "multiQueryEnabled", true);

        List<VectorSearchService.SearchResult> result = invokeMultiQuerySearch(service, "query");

        assertTrue(result.isEmpty(), "变体生成异常时应整路放弃，返回空列表（降级两路）");
    }
}
