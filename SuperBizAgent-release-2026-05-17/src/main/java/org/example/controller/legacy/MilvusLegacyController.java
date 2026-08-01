package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Milvus 旧路径控制器
 * 将旧版 /milvus/health 路径 301 重定向到新版 /api/v1/milvus/health 路径
 */
@RestController
@RequestMapping("/milvus")
public class MilvusLegacyController {

    /**
     * 旧版 Milvus 健康检查路径，重定向到 /api/v1/milvus/health
     */
    @GetMapping("/health")
    public ResponseEntity<Void> health() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/milvus/health").build();
    }
}
