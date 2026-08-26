package org.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 检索能力查询工具（Agentic RAG）
 * <p>
 * 告知 Agent 当前知识库检索系统的能力边界，包括：
 * - 知识库覆盖范围
 * - topK 范围
 * - 可用的检索模式和改写策略
 * <p>
 * Agent 首次处理检索任务时可调用此工具了解可用能力。
 * <p>
 * 仅在 rag.agentic.enabled=true 时注册为 Bean。
 */
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class GetSearchCapabilitiesTool {

    private static final Logger logger = LoggerFactory.getLogger(GetSearchCapabilitiesTool.class);

    @Value("${rag.top-k:3}")
    private int defaultTopK;

    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rewrite.strategy:direct}")
    private String rewriteStrategy;

    @Value("${rag.multi-query.enabled:false}")
    private boolean multiQueryEnabled;

    @Value("${rag.agentic.max-search-rounds:3}")
    private int maxSearchRounds;

    @Value("${rag.agentic.min-relevance-score:0.6}")
    private double minRelevanceScore;

    @Tool(description = """
            获取当前知识库检索系统能力信息，包括 topK 范围、可用检索模式、
            知识库覆盖范围、改写策略等。Agent 首次处理检索任务时可调用此工具了解能力边界。""")
    public String getSearchCapabilities() {
        logger.debug("getSearchCapabilities 被调用");

        String hybridStatus = hybridEnabled ? "enabled" : "disabled";
        String rerankStatus = rerankEnabled ? "enabled" : "disabled";
        String multiQueryStatus = multiQueryEnabled ? "enabled" : "disabled";

        return String.format("""
                {
                  "knowledgeBase": "内部运维文档（CPU高负载/内存高负载/磁盘高负载/服务不可用/响应延迟等）以及用户上传的文档",
                  "defaultTopK": %d,
                  "maxTopK": 20,
                  "searchModes": ["hybrid_dense_bm25_%s", "multi_query_%s"],
                  "capabilities": ["keyword_search", "semantic_search", "rerank_%s", "multi_query_expansion_%s"],
                  "queryRewriteStrategies": ["prompt_rewrite", "hypothetical_answer", "detail_abstract", "direct"],
                  "activeRewriteStrategy": "%s",
                  "agenticLimits": {
                    "maxSearchRounds": %d,
                    "minRelevanceScore": %.1f
                  },
                  "tools": {
                    "searchKnowledgeBase": "执行检索，支持指定 topK",
                    "evaluateSearchResults": "评估检索结果相关性",
                    "refineQuery": "根据反馈改写查询",
                    "decomposeQuestion": "拆解复杂问题为子问题",
                    "queryInternalDocs": "简化版检索（一次调用，不拆解）"
                  }
                }""",
                defaultTopK, hybridStatus, multiQueryStatus, rerankStatus, multiQueryStatus,
                rewriteStrategy, maxSearchRounds, minRelevanceScore);
    }
}
