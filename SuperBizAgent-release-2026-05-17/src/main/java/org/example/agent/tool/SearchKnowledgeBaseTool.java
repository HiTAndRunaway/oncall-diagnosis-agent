package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.AgenticRagGuard;
import org.example.service.AgenticRagGuard.RoundInfo;
import org.example.service.VectorSearchService;
import org.example.service.rewrite.QueryRewriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（Agentic RAG）
 * <p>
 * 比 queryInternalDocs 更细粒度：支持指定 topK，
 * 返回结果附带 _meta 信息（当前轮次/剩余轮次），
 * 供 Agent 判断是否继续检索。
 * <p>
 * 仅在 rag.agentic.enabled=true 时注册为 Bean。
 */
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class SearchKnowledgeBaseTool {

    private static final Logger logger = LoggerFactory.getLogger(SearchKnowledgeBaseTool.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private AgenticRagGuard guard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = """
            检索内部知识库。调用前自动用 QueryRewrite 改写查询，
            执行混合检索（向量+BM25+RRF融合+Rerank重排序），返回 topK 条文档。
            返回 JSON 中附带 _meta 信息标识当前检索轮次，Agent 据此判断是否继续检索。""")
    public String searchKnowledgeBase(
            @ToolParam(description = "检索查询文本") String query,
            @ToolParam(description = "返回文档数量，默认5，最大20") Integer topK) {

        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
            RoundInfo info = guard.beforeSearch();

            // 自动改写查询
            String rewrittenQuery = queryRewriteService.rewrite(query);
            logger.info("AgenticRAG 检索: round={}, query=[{}] → rewritten=[{}], topK={}",
                    info.round(), query, rewrittenQuery, k);

            // 执行检索（含双路召回+RRF+Rerank）
            List<VectorSearchService.SearchResult> results =
                    vectorSearchService.searchSimilarDocuments(rewrittenQuery, k);

            // 构建返回 JSON
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("_meta", Map.of(
                    "round", info.round(),
                    "maxRounds", info.maxRounds(),
                    "remainingRounds", info.remainingRounds()
            ));
            response.put("query", query);
            response.put("rewrittenQuery", rewrittenQuery);
            response.put("totalResults", results.size());
            response.put("results", results);

            String json = objectMapper.writeValueAsString(response);
            logger.info("AgenticRAG 检索完成: {} 条结果, remainingRounds={}",
                    results.size(), info.remainingRounds());
            return json;

        } catch (Exception e) {
            logger.error("AgenticRAG 检索失败", e);
            RoundInfo info = guard.currentRound();
            return String.format(
                    "{\"_meta\":{\"round\":%d,\"maxRounds\":%d,\"remainingRounds\":%d}," +
                    "\"error\":\"%s\",\"totalResults\":0,\"results\":[]}",
                    info.round(), info.maxRounds(), info.remainingRounds(),
                    ToolUtils.escapeJson(e.getMessage()));
        }
    }
}
