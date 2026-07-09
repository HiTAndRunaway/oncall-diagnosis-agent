package org.example.service.rewrite;

/**
 * 查询改写策略接口
 * <p>
 * 四种策略实现：
 * <ul>
 *   <li>{@code prompt_rewrite} — LLM 改写 prompt 使其更易懂</li>
 *   <li>{@code hypothetical_answer} — LLM 先生成假设答案</li>
 *   <li>{@code detail_abstract} — 判断细节/宏观问题并反向转换</li>
 *   <li>{@code direct} — 直接返回原始 query</li>
 * </ul>
 */
@FunctionalInterface
public interface QueryRewriteStrategy {

    /**
     * 改写查询文本
     *
     * @param originalQuery 原始用户问题
     * @return 改写后的文本（用于 embedding）
     */
    String rewrite(String originalQuery);

    /**
     * 是否需要 LLM 调用
     * 用于协调服务判断是否跳过缓存和重试逻辑
     */
    default boolean requiresLlm() {
        return true;
    }

    /**
     * 截断文本用于日志输出（最多 100 字符）
     */
    default String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
