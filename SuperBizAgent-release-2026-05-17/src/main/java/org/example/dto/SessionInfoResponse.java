package org.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 会话信息响应 DTO
 */
@Schema(description = "会话信息响应")
public class SessionInfoResponse {

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "消息对数", example = "5")
    private int messagePairCount;

    @Schema(description = "创建时间戳")
    private long createTime;

    @Schema(description = "消息列表")
    private List<Map<String, String>> messages;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getMessagePairCount() {
        return messagePairCount;
    }

    public void setMessagePairCount(int messagePairCount) {
        this.messagePairCount = messagePairCount;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public List<Map<String, String>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, String>> messages) {
        this.messages = messages;
    }
}
