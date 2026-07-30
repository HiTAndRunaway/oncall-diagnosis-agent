package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 表示智能体执行过程中发出的事件，用于通过 SSE 进行流式响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEvent {

    private final EventType type;
    private final String data;
    private final String sessionId;

    private AgentEvent(EventType type, String data, String sessionId) {
        this.type = type;
        this.data = data;
        this.sessionId = sessionId;
    }

    public static AgentEvent content(String chunk) {
        return new AgentEvent(EventType.CONTENT_CHUNK, chunk, null);
    }

    public static AgentEvent toolCallStart(String name) {
        return new AgentEvent(EventType.TOOL_CALL_START, name, null);
    }

    public static AgentEvent toolCallEnd(String name) {
        return new AgentEvent(EventType.TOOL_CALL_END, name, null);
    }

    public static AgentEvent error(String msg) {
        return new AgentEvent(EventType.ERROR, msg, null);
    }

    public static AgentEvent done(String sessionId) {
        return new AgentEvent(EventType.DONE, null, sessionId);
    }

    public EventType getType() {
        return type;
    }

    public String getData() {
        return data;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * 事件类型枚举，包含用于序列化的 JSON 字符串值。
     */
    public enum EventType {
        CONTENT_CHUNK("content"),
        TOOL_CALL_START("tool_start"),
        TOOL_CALL_END("tool_end"),
        ERROR("error"),
        DONE("done");

        private final String value;

        EventType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }
    }
}
