package org.example.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.example.config.DashScopeApiProperties;
import org.example.exception.LlmServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * DashScope implementation of {@link LlmProvider}.
 * Encapsulates DashScopeChatModel creation and invocation.
 */
@Component
public class DashScopeLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeLlmProvider.class);

    @Autowired
    private DashScopeApiProperties dashScopeApiProperties;

    @Override
    @CircuitBreaker(name = "dashscope-llm", fallbackMethod = "chatFallback")
    public String chat(String systemMessage, String userMessage, ChatOptions options) {
        DashScopeChatModel model = buildModel(options);
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
        DashScopeChatModel model = buildModel(options);
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
        throw new LlmServiceException("DashScope", "AI 服务暂时不可用，系统已自动熔断保护，预计 30 秒后恢复");
    }

    /**
     * Build a DashScopeChatModel from the given options.
     */
    private DashScopeChatModel buildModel(ChatOptions options) {
        DashScopeApi api = DashScopeApi.builder().apiKey(dashScopeApiProperties.getKey()).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(options.model())
                        .withTemperature(options.temperature())
                        .withMaxToken(options.maxToken())
                        .withTopP(options.topP())
                        .build())
                .build();
    }
}
