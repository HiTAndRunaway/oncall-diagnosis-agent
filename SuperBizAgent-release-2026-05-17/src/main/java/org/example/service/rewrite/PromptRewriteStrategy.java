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

    private final ChatModel chatModel;

    public PromptRewriteStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String rewrite(String originalQuery) {
        String prompt = String.format(REWRITE_PROMPT_TEMPLATE, originalQuery);
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
