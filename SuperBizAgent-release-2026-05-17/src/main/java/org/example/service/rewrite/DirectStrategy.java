package org.example.service.rewrite;

/**
 * 策略4：直接召回
 * <p>
 * 不做任何改写，直接返回原始 query。无需 LLM 调用。
 */
public class DirectStrategy implements QueryRewriteStrategy {

    @Override
    public String rewrite(String originalQuery) {
        return originalQuery;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }
}
