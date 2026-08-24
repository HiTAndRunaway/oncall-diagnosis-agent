package org.example.agent;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.example.config.ChatModelFactory;
import org.example.exception.LlmServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * liteLLM 网关实现（OpenAI 兼容）的 {@link LlmProvider}。
 * <p>
 * 仅在 {@code litellm.enabled=true} 时注册（与 {@code DashScopeLlmProvider} 二选一），
 * 通过 {@link ChatModelFactory} 构建指向 liteLLM 的 OpenAiChatModel。
 */
@Component
@ConditionalOnProperty(name = "litellm.enabled", havingValue = "true")
public class LiteLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmProvider.class);

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Override
    @CircuitBreaker(name = "litellm-llm", fallbackMethod = "chatFallback")
    public String chat(String systemMessage, String userMessage, ChatOptions options) {
        ChatModel model = chatModelFactory.create(options.model(), options.temperature(), options.maxToken(), options.topP());
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemMessage),
                        new UserMessage(userMessage)
                )
        );
        ChatResponse response = model.call(prompt);
        return response.getResult().getOutput().getText();
    }

    @Override
    public Flux<String> chatStream(String systemMessage, String userMessage, ChatOptions options) {
        ChatModel model = chatModelFactory.create(options.model(), options.temperature(), options.maxToken(), options.topP());
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemMessage),
                        new UserMessage(userMessage)
                )
        );
        return model.stream(prompt)
                .map(response -> response.getResult().getOutput().getText());
    }

    /**
     * Circuit breaker fallback method.
     * Logs the failure and throws a business exception for the global handler to process.
     */
    @SuppressWarnings("unused")
    private String chatFallback(String systemMessage, String userMessage, ChatOptions options, Throwable t) {
        log.warn("[CircuitBreaker] LLM 服务降级 - error: {}", t.getMessage());
        throw new LlmServiceException("LiteLLM", "AI 服务暂时不可用，系统已自动熔断保护，预计 30 秒后恢复");
    }
}
