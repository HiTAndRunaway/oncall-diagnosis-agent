package org.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录结果 DTO
 */
@Schema(description = "登录结果")
public class LoginResult {

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "用户描述信息")
    private String description;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
