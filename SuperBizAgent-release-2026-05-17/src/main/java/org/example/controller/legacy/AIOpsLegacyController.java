package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AIOps 旧路径控制器
 * 将旧版 /api/ai_ops 路径 301 重定向到新版 /api/v1/ai_ops 路径
 */
@RestController
@RequestMapping("/api")
public class AIOpsLegacyController {

    /**
     * 旧版 AIOps 告警分析路径，重定向到 /api/v1/ai_ops
     */
    @PostMapping("/ai_ops")
    public ResponseEntity<Void> aiOps() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/ai_ops").build();
    }
}
