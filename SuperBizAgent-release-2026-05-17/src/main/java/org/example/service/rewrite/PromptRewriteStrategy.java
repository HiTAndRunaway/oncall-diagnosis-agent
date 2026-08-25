package org.example.service.rewrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 策略1：Prompt 改写
 * <p>
 * 调用 LLM 将用户的原始 prompt 改写成更清晰、更易被检索系统理解的表达方式，
 * 然后使用改写后的文本生成向量进行召回。
 * <p>
 * 同时支持"依赖检索评估反馈"的改写（Agentic RAG refine 环节）：
 * 当传入 feedback 时，结合上一轮评估结果（哪些结果无关/缺失什么信息）生成更有针对性的查询。
 */
public class PromptRewriteStrategy implements QueryRewriteStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PromptRewriteStrategy.class);

    private static final String REWRITE_PROMPT_TEMPLATE = """
            你是一个查询优化助手。请将用户的问题改写成更清晰、更易被检索系统理解的\
            表达方式。

            要求：
            - 保留原始问题的全部语义
            - 补充隐含的上下文和关键术语
            - 使用更规范、更具体的表述
            - 直接输出改写结果，不要输出任何解释

            用户问题：%s
            改写结果：""";

    /** 结合检索评估反馈的改写模板（Agentic RAG refine 环节专用） */
    private static final String REWRITE_WITH_FEEDBACK_PROMPT_TEMPLATE = """
            你是一个查询优化助手。上一轮针对该问题的知识库检索结果质量不佳。
            请根据下面的"检索质量反馈"，把用户问题改写成一个能检索到更多、更相关内容的更好查询。

            要求：
            - 保留原始问题的核心意图
            - 结合反馈中指出的"无关/缺失/不足"信息，补充或替换检索关键词与表达
            - 使改写后的查询更适合向量检索，能命中知识库中真正相关的内容
            - 直接输出改写结果，不要输出任何解释或前缀

            用户原始问题：%s

            检索质量反馈：
            %s

            改写结果：""";

    private final ChatModel chatModel;

    public PromptRewriteStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String rewrite(String originalQuery) {
        String prompt = String.format(REWRITE_PROMPT_TEMPLATE, escapePercent(originalQuery));
        return callRewrite(prompt, originalQuery);
    }

    @Override
    public String rewrite(String originalQuery, String feedback) {
        if (feedback == null || feedback.trim().isEmpty()) {
            return rewrite(originalQuery);
        }
        String prompt = String.format(REWRITE_WITH_FEEDBACK_PROMPT_TEMPLATE,
                escapePercent(originalQuery), escapePercent(feedback.trim()));
        logger.debug("策略1(prompt_rewrite): 结合反馈改写查询, feedback长度={}", feedback.trim().length());
        return callRewrite(prompt, originalQuery);
    }

    /**
     * 转义 {@code %}，避免用户查询/LLM 反馈中出现 {@code %} 导致 String.format 抛异常。
     */
    private static String escapePercent(String s) {
        return s == null ? null : s.replace("%", "%%");
    }

    /**
     * 统一执行一次 LLM 改写调用，并对空结果降级为原始 query。
     */
    private String callRewrite(String prompt, String originalQuery) {
        logger.debug("策略1(prompt_rewrite): 开始调用 LLM 改写查询");

        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        String rewritten = response.getResult().getOutput().getText();

        if (rewritten == null || rewritten.trim().isEmpty()) {
            logger.warn("策略1(prompt_rewrite): LLM 返回空内容，降级为原始 query");
            return originalQuery;
        }

        String result = rewritten.trim();
        logger.info("策略1(prompt_rewrite): 改写成功, original=[{}] → rewritten=[{}]",
                truncate(originalQuery), truncate(result));
        return result;
    }
}
