package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上传旧路径控制器
 * 将旧版 /api/upload 系列路径 301 重定向到新版 /api/v1/upload 路径
 */
@RestController
@RequestMapping("/api")
public class UploadLegacyController {

    /**
     * 旧版文件上传路径，重定向到 /api/v1/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<Void> upload() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/upload").build();
    }

    /**
     * 旧版失败索引重建路径，重定向到 /api/v1/upload/reindex-failed
     */
    @PostMapping("/upload/reindex-failed")
    public ResponseEntity<Void> reindexFailed() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/upload/reindex-failed").build();
    }
}
