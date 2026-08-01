package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证旧路径控制器
 * 将旧版 /api/login 路径 301 重定向到新版 /api/v1/login 路径
 */
@RestController
@RequestMapping("/api")
public class AuthLegacyController {

    /**
     * 旧版登录路径，重定向到 /api/v1/login
     */
    @PostMapping("/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/login").build();
    }
}
