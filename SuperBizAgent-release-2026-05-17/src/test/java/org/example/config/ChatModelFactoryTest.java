package org.example.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatModelFactory 开关切换单元测试（不依赖网络/LLM）
 * <p>
 * 验证：litellm.enabled=false → DashScopeChatModel（现状）；
 * litellm.enabled=true → OpenAiChatModel（指向 liteLLM）；
 * 以及模型参数（model/temperature/maxToken/topP）正确透传到 defaultOptions。
 */
class ChatModelFactoryTest {

    private static final String MODEL = "qwen3-max";
    private static final double TEMP = 0.7;
    private static final int MAX_TOKEN = 2000;
    private static final double TOP_P = 0.9;

    private static final ModelProperties.ModelConfig CFG =
            new ModelProperties.ModelConfig(MODEL, TEMP, MAX_TOKEN, TOP_P);

    private static ChatModelFactory newFactory(boolean gatewayEnabled) {
        LiteLlmProperties lite = new LiteLlmProperties();
        lite.setEnabled(gatewayEnabled);
        lite.setBaseUrl("http://localhost:4000/");   // 带尾部斜杠，验证规范化
        lite.setApiKey("sk-test");
        DashScopeApiProperties dash = new DashScopeApiProperties();
        dash.setKey("test-dashscope-key");
        return new ChatModelFactory(lite, dash);
    }

    @Test
    void disabled_shouldReturnDashScopeModel() {
        ChatModelFactory factory = newFactory(false);

        ChatModel model = factory.create(CFG);

        assertNotNull(model);
        assertInstanceOf(DashScopeChatModel.class, model,
                "enabled=false 时应返回 DashScopeChatModel（保持现状）");
    }

    @Test
    void enabled_shouldReturnOpenAiModel() {
        ChatModelFactory factory = newFactory(true);

        ChatModel model = factory.create(CFG);

        assertNotNull(model);
        assertInstanceOf(OpenAiChatModel.class, model,
                "enabled=true 时应返回 OpenAiChatModel（指向 liteLLM 网关）");
    }

    @Test
    void disabled_shouldPassThroughParamsToDashScopeOptions() {
        ChatModelFactory factory = newFactory(false);

        DashScopeChatModel model = (DashScopeChatModel) factory.create(CFG);
        DashScopeChatOptions opts = (DashScopeChatOptions) model.getDefaultOptions();

        assertEquals(MODEL, opts.getModel());
        assertEquals(TEMP, opts.getTemperature());
        assertEquals(MAX_TOKEN, opts.getMaxTokens());
        assertEquals(TOP_P, opts.getTopP());
    }

    @Test
    void enabled_shouldPassThroughParamsToOpenAiOptions() {
        ChatModelFactory factory = newFactory(true);

        OpenAiChatModel model = (OpenAiChatModel) factory.create(CFG);
        OpenAiChatOptions opts = (OpenAiChatOptions) model.getDefaultOptions();

        assertEquals(MODEL, opts.getModel());
        assertEquals(TEMP, opts.getTemperature());
        assertEquals(MAX_TOKEN, opts.getMaxTokens());
        assertEquals(TOP_P, opts.getTopP());
    }

    @Test
    void baseUrl_shouldBeNormalizedWithoutTrailingSlash() {
        LiteLlmProperties props = new LiteLlmProperties();
        props.setBaseUrl("http://localhost:4000/");
        assertEquals("http://localhost:4000", props.getBaseUrl());
    }

    @Test
    void overload_shouldCreateWithExplicitParams() {
        ChatModelFactory factory = newFactory(true);

        ChatModel model = factory.create("qwen-turbo", 0.3, 500, 0.9);

        assertNotNull(model);
        assertInstanceOf(OpenAiChatModel.class, model);
    }
}
