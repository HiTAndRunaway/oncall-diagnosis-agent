package org.example.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.config.ApiKeyProperties;
import org.example.dto.ApiResponse;
import org.example.dto.LoginRequest;
import org.example.dto.LoginResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 V1 控制器
 * POST /api/v1/login：验证 API Key，返回 userId + description
 */
@Tag(name = "认证", description = "用户认证与 API Key 验证接口")
@RestController
@RequestMapping("/api/v1")
public class AuthV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(AuthV1Controller.class);

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Operation(summary = "登录认证", description = "使用 API Key 进行身份认证，返回用户ID和描述信息")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "认证成功",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "API Key 无效或缺失")
    })
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
}
