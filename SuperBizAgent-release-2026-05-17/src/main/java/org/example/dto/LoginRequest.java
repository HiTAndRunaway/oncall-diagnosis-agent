package org.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录请求 DTO
 */
@Schema(description = "登录请求")
public class LoginRequest {

    @Schema(description = "API Key", example = "dev-api-key-change-me")
    private String apiKey;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
