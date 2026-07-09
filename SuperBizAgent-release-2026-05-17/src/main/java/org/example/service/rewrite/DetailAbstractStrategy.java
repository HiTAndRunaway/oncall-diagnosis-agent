package org.example.service.rewrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 策略3：细节/宏观判断与转换
 * <p>
 * 一次 LLM 调用完成：
 * <ol>
 *   <li>判断用户问题是"细节问题"还是"宏观问题"</li>
 *   <li>细节问题 → 抽象为宏观方法论问题</li>
 *   <li>宏观问题 → 细化为具体可操作问题</li>
 * </ol>
 */
public class DetailAbstractStrategy implements QueryRewriteStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DetailAbstractStrategy.class);

    private static final String TRANSFORM_PROMPT_TEMPLATE = """
            你是一个查询优化助手。请先判断用户问题的类型，然后进行相应转换。

            判断规则：
            - 细节问题：包含具体的指标、数值、步骤、工具名、实例名等
            - 宏观问题：概念性、方法论、概述性的宽泛问题

            转换规则：
            - 若是细节问题 → 将其抽象为宏观的方法论问题
            - 若是宏观问题 → 将其细化为具体的可操作问题

            请按以下格式输出（只输出转换结果，不要解释）：
            {转换后的问题}

            用户问题：%s""";

    private final ChatModel chatModel;

    public DetailAbstractStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String rewrite(String originalQuery) {
        String prompt = String.format(TRANSFORM_PROMPT_TEMPLATE, originalQuery);
        logger.debug("策略3(detail_abstract): 开始调用 LLM 进行判断与转换");

        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        String transformed = response.getResult().getOutput().getText();

        if (transformed == null || transformed.trim().isEmpty()) {
            logger.warn("策略3(detail_abstract): LLM 返回空内容，降级为原始 query");
            return originalQuery;
        }

        String result = transformed.trim();
        logger.info("策略3(detail_abstract): 转换成功, original=[{}] → transformed=[{}]",
                truncate(originalQuery), truncate(result));
        return result;
    }
}
