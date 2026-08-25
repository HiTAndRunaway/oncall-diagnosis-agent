package org.example.agent.tool;

import org.example.service.rewrite.QueryRewriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 查询改写工具（Agentic RAG）
 * <p>
 * 当检索结果不理想时，Agent 可调用此工具根据评估反馈改进查询文本。
 * 内部复用 QueryRewriteService 的策略模式。
 * <p>
 * 仅在 rag.agentic.enabled=true 时注册为 Bean。
 */
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class RefineQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(RefineQueryTool.class);

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Tool(description = """
            根据评估反馈改写/扩写查询文本。当 evaluateSearchResults 的
            recommendation 为 REFINE 时调用，用反馈信息生成更精确的查询。
            内部复用 QueryRewriteService 的策略模式进行改写。""")
    public String refineQuery(
            @ToolParam(description = "原始查询") String query,
            @ToolParam(description = "评估反馈文本，从 evaluateSearchResults 的 summary/evaluations 中提取")
            String feedback) {

        try {
            logger.info("refineQuery: query=[{}], feedback=[{}]",
                    ToolUtils.truncate(query, 100), ToolUtils.truncate(feedback, 200));

            // 使用 QueryRewriteService 进行反馈感知改写：
            // 将 evaluateSearchResults 的评估反馈（summary/evaluations）传入，让改写后的查询
            // 更有针对性（哪些结果无关、缺什么信息）。feedback 为空时退化为普通改写。
            String rewritten = queryRewriteService.rewriteWithFeedback(query, feedback);

            if (rewritten == null || rewritten.trim().isEmpty() || rewritten.equals(query)) {
                logger.warn("refineQuery: 改写未产生变化，返回原查询");
                return String.format(
                        "{\"refinedQuery\": \"%s\", \"note\": \"改写未产生显著变化，建议调整检索方向\"}",
                        ToolUtils.escapeJson(query));
            }

            logger.info("refineQuery 完成: [{}] → [{}]",
                    ToolUtils.truncate(query, 100), ToolUtils.truncate(rewritten, 100));
            return String.format("{\"refinedQuery\": \"%s\", \"originalQuery\": \"%s\"}",
                    ToolUtils.escapeJson(rewritten), ToolUtils.escapeJson(query));

        } catch (Exception e) {
            logger.warn("refineQuery 异常，返回原查询: {}", e.getMessage());
            return String.format(
                    "{\"refinedQuery\": \"%s\", \"note\": \"改写服务异常，使用原查询\"}",
                    ToolUtils.escapeJson(query));
        }
    }
}
