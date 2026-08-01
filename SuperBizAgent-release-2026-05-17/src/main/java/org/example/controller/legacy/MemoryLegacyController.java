package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆旧路径控制器
 * 将旧版 /api/memory 系列路径 301 重定向到新版 /api/v1/memory 路径
 */
@RestController
@RequestMapping("/api")
public class MemoryLegacyController {

    /**
     * 旧版记忆面板路径，重定向到 /api/v1/memory/panel
     */
    @GetMapping("/memory/panel")
    public ResponseEntity<Void> memoryPanel() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/memory/panel").build();
    }

    /**
     * 旧版删除单条记忆路径，重定向到 /api/v1/memory/{memoryId}
     */
    @DeleteMapping("/memory/{memoryId}")
    public ResponseEntity<Void> deleteMemory(@PathVariable String memoryId) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/memory/" + memoryId).build();
    }

    /**
     * 旧版清空记忆路径，重定向到 /api/v1/memory/clear
     */
    @DeleteMapping("/memory/clear")
    public ResponseEntity<Void> clearMemory() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/memory/clear").build();
    }
}
