package org.example.controller;

import org.example.service.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 记忆管理 REST API
 * 提供前端「我的记忆」面板的数据查询、删除操作
 */
@RestController
@RequestMapping("/api/memory")
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryController {

    private static final Logger logger = LoggerFactory.getLogger(MemoryController.class);

    @Autowired
    private MemoryManager memoryManager;

    /**
     * 获取用户所有记忆面板数据（按类型分组）
     */
    @GetMapping("/panel")
    public ResponseEntity<Map<String, Object>> getMemoryPanel() {

        String userId = getCurrentUserId();
        logger.info("获取记忆面板 - userId={}", userId);

        if (userId == null || userId.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "userId is required");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, List<MemoryManager.MemoryResult>> grouped =
                    memoryManager.getAllMemories(userId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("userId", userId);

            response.put("facts", formatForFrontend(
                    grouped.getOrDefault("facts", Collections.emptyList())));
            response.put("profiles", formatForFrontend(
                    grouped.getOrDefault("profiles", Collections.emptyList())));
            response.put("preferences", formatForFrontend(
                    grouped.getOrDefault("preferences", Collections.emptyList())));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("获取记忆面板失败", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "获取记忆失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 删除单条记忆
     */
    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteMemory(
            @PathVariable("memoryId") String memoryId) {

        String userId = getCurrentUserId();
        logger.info("删除记忆 - userId={}, memoryId={}", userId, memoryId);

        boolean success = memoryManager.deleteMemory(userId, memoryId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("message", success ? "记忆已删除" : "删除失败");

        return ResponseEntity.ok(response);
    }

    /**
     * 清空用户所有记忆
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearMemories() {

        String userId = getCurrentUserId();
        logger.info("清空记忆 - userId={}", userId);

        long deleted = memoryManager.deleteAllMemories(userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "已清空全部记忆");
        response.put("deletedCount", deleted);

        return ResponseEntity.ok(response);
    }

    /**
     * 从 SecurityContext 获取当前用户 ID
     * 安全关闭时返回 "anonymous" 以保持向后兼容
     */
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        return "anonymous";
    }

    private List<Map<String, Object>> formatForFrontend(List<MemoryManager.MemoryResult> memories) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MemoryManager.MemoryResult m : memories) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("type", m.getType());
            item.put("content", m.getContent());
            item.put("confidence", Math.round(m.getConfidence() * 100.0) / 100.0);
            item.put("confidencePercent", Math.round(m.getConfidence() * 100));
            item.put("sourceSession", m.getSourceSession());
            item.put("createdAt", m.getCreatedAt());
            item.put("lastAccessedAt", m.getLastAccessedAt());
            item.put("decayCount", m.getDecayCount());
            result.add(item);
        }
        return result;
    }
}
