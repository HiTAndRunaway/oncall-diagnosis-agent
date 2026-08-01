package org.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一聊天响应 DTO
 */
@Schema(description = "聊天响应")
public class ChatResponse {

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "AI 回答内容")
    private String answer;

    @Schema(description = "错误信息（仅失败时）")
    private String errorMessage;

    @Schema(description = "会话ID")
    private String sessionId;

    public static ChatResponse success(String answer, String sessionId) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(true);
        response.setAnswer(answer);
        response.setSessionId(sessionId);
        return response;
    }

    public static ChatResponse error(String errorMessage) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
