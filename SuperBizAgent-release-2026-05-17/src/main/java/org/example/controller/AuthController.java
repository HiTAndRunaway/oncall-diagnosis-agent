package org.example.controller;

import org.example.config.ApiKeyProperties;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * POST /api/login：验证 API Key，返回 userId + description
 * 该端点在 SecurityConfig 白名单中，无需认证即可访问
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResult>> login(@RequestBody LoginRequest request) {
        if (request.getApiKey() == null || request.getApiKey().isEmpty()) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "API Key is required"));
        }

        ApiKeyProperties.ApiKeyEntry entry = apiKeyProperties.lookup(request.getApiKey());
        if (entry == null) {
            logger.warn("Login failed: invalid API Key");
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid API Key"));
        }

        logger.info("Login successful for user: {}", entry.getUserId());
        LoginResult result = new LoginResult();
        result.setUserId(entry.getUserId());
        result.setDescription(entry.getDescription() != null ? entry.getDescription() : "");
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== 内部类 ====================

    /**
     * 登录请求
     */
    public static class LoginRequest {
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * 登录结果
     */
    public static class LoginResult {
        private String userId;
        private String description;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
