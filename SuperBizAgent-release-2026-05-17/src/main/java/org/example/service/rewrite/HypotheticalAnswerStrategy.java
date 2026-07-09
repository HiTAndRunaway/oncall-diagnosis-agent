package org.example.service.rewrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 策略2：假设答案
 * <p>
 * 调用 LLM 对用户的问题生成简要回答，然后将该回答生成向量进行召回。
 * 适用于用户问题过于简短或模糊的场景。
 */
public class HypotheticalAnswerStrategy implements QueryRewriteStrategy {

    private static final Logger logger = LoggerFactory.getLogger(HypotheticalAnswerStrategy.class);

    private static final String ANSWER_PROMPT_TEMPLATE = """
            请对以下问题给出简要回答。不需要过于详细的解释，但需要覆盖核心要点。

            要求：
            - 回答控制在 200 字以内
            - 涵盖问题的核心知识点
            - 直接输出回答内容，不要输出任何前缀和解释

            问题：%s
            回答：""";

    private final ChatModel chatModel;

    public HypotheticalAnswerStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String rewrite(String originalQuery) {
        String prompt = String.format(ANSWER_PROMPT_TEMPLATE, originalQuery);
        logger.debug("策略2(hypothetical_answer): 开始调用 LLM 生成假设答案");

        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        String answer = response.getResult().getOutput().getText();

        if (answer == null || answer.trim().isEmpty()) {
            logger.warn("策略2(hypothetical_answer): LLM 返回空内容，降级为原始 query");
            return originalQuery;
        }

        String result = answer.trim();
        logger.info("策略2(hypothetical_answer): 假设答案生成成功, original=[{}] → answer=[{}]",
                truncate(originalQuery), truncate(result));
        return result;
    }
}
