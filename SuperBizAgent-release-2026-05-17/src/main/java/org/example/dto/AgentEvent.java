package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents an event emitted during agent execution, used for
 * streaming responses via SSE.
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
     * Event type enumeration with JSON string values for serialization.
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
