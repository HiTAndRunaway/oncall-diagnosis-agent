package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ChatModel 统一构建工厂（liteLLM 网关收敛点）
 * <p>
 * 按 {@link LiteLlmProperties#isEnabled()} 开关返回：
 * <ul>
 *   <li>enabled=false（默认）→ {@link DashScopeChatModel}（DashScope 直连，现状行为）</li>
 *   <li>enabled=true → {@link OpenAiChatModel}（base-url 指向 liteLLM OpenAI 兼容网关）</li>
 * </ul>
 * 收敛 ReactAgentRunner / AIOpsEvaluator / IntentRouter / SummaryGenerator / QueryRewriteService
 * 等处对模型的直接构建，使网关切换只需改配置开关，无需改业务代码。
 * <p>
 * API 层对象（OpenAiApi / DashScopeApi）不可变且线程安全，做单例缓存避免每次调用
 * 重复创建 WebClient 等较重对象；ChatModel 因 defaultOptions 参数不同按需新建。
 */
@Component
public class ChatModelFactory {

    private final LiteLlmProperties liteLlmProperties;
    private final DashScopeApiProperties dashScopeApiProperties;

    private volatile OpenAiApi cachedOpenAiApi;
    private volatile DashScopeApi cachedDashScopeApi;

    @Autowired
    public ChatModelFactory(LiteLlmProperties liteLlmProperties,
                            DashScopeApiProperties dashScopeApiProperties) {
        this.liteLlmProperties = liteLlmProperties;
        this.dashScopeApiProperties = dashScopeApiProperties;
    }

    /**
     * 按开关创建 ChatModel。
     *
     * @param config 模型名称与参数（来自 ai.model.* 分层配置）
     * @return ChatModel（DashScope 或 OpenAI 兼容实现）
     */
    public ChatModel create(ModelProperties.ModelConfig config) {
        if (liteLlmProperties.isEnabled()) {
            return createOpenAiModel(config);
        }
        return createDashScopeModel(config);
    }

    /**
     * 便捷重载：直接传参创建 ChatModel。
     *
     * @param model       模型名称
     * @param temperature 温度
     * @param maxToken    最大输出 token
     * @param topP        top-p 采样
     * @return ChatModel
     */
    public ChatModel create(String model, double temperature, int maxToken, double topP) {
        return create(new ModelProperties.ModelConfig(model, temperature, maxToken, topP));
    }

    private OpenAiChatModel createOpenAiModel(ModelProperties.ModelConfig config) {
        return OpenAiChatModel.builder()
                .openAiApi(getOpenAiApi())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.getName())
                        .temperature(config.getTemperature())
                        .maxTokens(config.getMaxToken())
                        .topP(config.getTopP())
                        .build())
                .build();
    }

    private DashScopeChatModel createDashScopeModel(ModelProperties.ModelConfig config) {
        return DashScopeChatModel.builder()
                .dashScopeApi(getDashScopeApi())
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(config.getName())
                        .withTemperature(config.getTemperature())
                        .withMaxToken(config.getMaxToken())
                        .withTopP(config.getTopP())
                        .build())
                .build();
    }

    private OpenAiApi getOpenAiApi() {
        OpenAiApi api = cachedOpenAiApi;
        if (api == null) {
            synchronized (this) {
                api = cachedOpenAiApi;
                if (api == null) {
                    api = OpenAiApi.builder()
                            .baseUrl(liteLlmProperties.getBaseUrl())
                            .apiKey(liteLlmProperties.getApiKey())
                            .build();
                    cachedOpenAiApi = api;
                }
            }
        }
        return api;
    }

    private DashScopeApi getDashScopeApi() {
        DashScopeApi api = cachedDashScopeApi;
        if (api == null) {
            synchronized (this) {
                api = cachedDashScopeApi;
                if (api == null) {
                    api = DashScopeApi.builder()
                            .apiKey(dashScopeApiProperties.getKey())
                            .build();
                    cachedDashScopeApi = api;
                }
            }
        }
        return api;
    }
}
