package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天旧路径控制器
 * 将旧版 /api/chat 系列路径 301 重定向到新版 /api/v1 路径
 */
@RestController
@RequestMapping("/api")
public class ChatLegacyController {

    /**
     * 旧版普通对话路径，重定向到 /api/v1/chat
     */
    @PostMapping("/chat")
    public ResponseEntity<Void> chat() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat").build();
    }

    /**
     * 旧版流式对话路径，重定向到 /api/v1/chat_stream
     */
    @PostMapping("/chat_stream")
    public ResponseEntity<Void> chatStream() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat_stream").build();
    }

    /**
     * 旧版清空会话路径，重定向到 /api/v1/chat/clear
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<Void> clearChatHistory() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat/clear").build();
    }

    /**
     * 旧版查询会话信息路径，重定向到 /api/v1/chat/session/{sessionId}
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<Void> getSessionInfo(@PathVariable String sessionId) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat/session/" + sessionId).build();
    }
}
