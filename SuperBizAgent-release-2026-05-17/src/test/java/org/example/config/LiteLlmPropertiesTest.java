package org.example.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LiteLlmProperties 单元测试（不依赖网络/LLM）
 * <p>
 * 验证：默认值（enabled=false 保持现状）、字段绑定、网关模式的 fail-fast 启动校验。
 */
class LiteLlmPropertiesTest {

    @Test
    void shouldHaveDefaults() {
        LiteLlmProperties props = new LiteLlmProperties();

        assertFalse(props.isEnabled(), "默认应关闭网关（enabled=false），保持 DashScope 直连");
        assertEquals("http://localhost:4000", props.getBaseUrl());
        assertEquals("", props.getApiKey());
    }

    @Test
    void shouldBindValues() {
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:9999");
        props.setApiKey("sk-test");

        assertTrue(props.isEnabled());
        assertEquals("http://localhost:9999", props.getBaseUrl());
        assertEquals("sk-test", props.getApiKey());
    }

    @Test
    void disabled_shouldNotValidateGatewaySettings() {
        // enabled=false 时即使 api-key 为空/占位符也不抛异常（保持现状启动）
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(false);
        props.setApiKey("");
        props.validate();
        // 不抛异常即通过
    }

    @Test
    void enabled_missingApiKey_shouldFailFast() {
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:4000");
        props.setApiKey("");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(ex.getMessage().contains("api-key"));
    }

    @Test
    void enabled_placeholderApiKey_shouldFailFast() {
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:4000");
        props.setApiKey("sk-litellm-change-me");

        assertThrows(IllegalStateException.class, props::validate);
    }

    @Test
    void enabled_missingBaseUrl_shouldFailFast() {
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl(null);
        props.setApiKey("sk-test");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(ex.getMessage().contains("base-url"));
    }

    @Test
    void enabled_validSettings_shouldPass() {
        LiteLlmProperties props = new LiteLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:4000");
        props.setApiKey("sk-real-key");
        props.validate();
        // 不抛异常即通过
    }
}
