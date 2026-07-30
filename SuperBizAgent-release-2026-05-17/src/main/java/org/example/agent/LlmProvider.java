package org.example.agent;

import reactor.core.publisher.Flux;

/**
 * LLM 服务提供者接口。
 * 抽象底层 LLM API，使系统能够与不同的提供商（DashScope、OpenAI 等）协作。
 */
public interface LlmProvider {

    /**
     * 同步聊天补全。
     *
     * @param systemMessage 系统消息 / 指令
     * @param userMessage   用户消息
     * @param options       聊天配置选项
     * @return 模型的文本响应
     */
    String chat(String systemMessage, String userMessage, ChatOptions options);

    /**
     * 流式聊天补全。
     *
     * @param systemMessage 系统消息 / 指令
     * @param userMessage   用户消息
     * @param options       聊天配置选项
     * @return 来自模型的文本块反应式流
     */
    Flux<String> chatStream(String systemMessage, String userMessage, ChatOptions options);

    /**
     * LLM 调用的聊天配置选项。
     */
    record ChatOptions(String model, double temperature, int maxToken, double topP) {

        /**
         * 标准聊天选项，参数均衡。
         *
         * @param model 模型名称
         * @return ChatOptions，包含 temperature=0.7, maxToken=2000, topP=0.9
         */
        public static ChatOptions standard(String model) {
            return new ChatOptions(model, 0.7, 2000, 0.9);
        }

        /**
         * AIOps 优化的聊天选项，较低温度与较高 token 限制。
         *
         * @param model 模型名称
         * @return ChatOptions，包含 temperature=0.3, maxToken=8000, topP=0.9
         */
        public static ChatOptions aiOps(String model) {
            return new ChatOptions(model, 0.3, 8000, 0.9);
        }
    }
}
