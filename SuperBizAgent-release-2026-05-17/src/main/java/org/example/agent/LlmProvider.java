package org.example.agent;

import reactor.core.publisher.Flux;

/**
 * Interface for LLM service providers.
 * Abstracts the underlying LLM API so the system can work with
 * different providers (DashScope, OpenAI, etc.).
 */
public interface LlmProvider {

    /**
     * Synchronous chat completion.
     *
     * @param systemMessage the system message / instructions
     * @param userMessage   the user message
     * @param options       chat configuration options
     * @return the model's text response
     */
    String chat(String systemMessage, String userMessage, ChatOptions options);

    /**
     * Streaming chat completion.
     *
     * @param systemMessage the system message / instructions
     * @param userMessage   the user message
     * @param options       chat configuration options
     * @return a reactive stream of text chunks from the model
     */
    Flux<String> chatStream(String systemMessage, String userMessage, ChatOptions options);

    /**
     * Chat configuration options for LLM calls.
     */
    record ChatOptions(String model, double temperature, int maxToken, double topP) {

        /**
         * Standard chat options with balanced parameters.
         *
         * @param model the model name
         * @return ChatOptions with temperature=0.7, maxToken=2000, topP=0.9
         */
        public static ChatOptions standard(String model) {
            return new ChatOptions(model, 0.7, 2000, 0.9);
        }

        /**
         * AIOps-optimized chat options with lower temperature and higher token limit.
         *
         * @param model the model name
         * @return ChatOptions with temperature=0.3, maxToken=8000, topP=0.9
         */
        public static ChatOptions aiOps(String model) {
            return new ChatOptions(model, 0.3, 8000, 0.9);
        }
    }
}
