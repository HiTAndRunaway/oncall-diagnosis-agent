package org.example.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.dto.ApiResponse;
import org.example.exception.BizException;
import org.example.exception.InvalidInputException;
import org.example.exception.ResourceNotFoundException;
import org.example.exception.ServiceUnavailableException;
import org.example.security.CurrentUser;
import org.example.service.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 记忆管理 V1 REST API
 * 提供前端「我的记忆」面板的数据查询、删除操作
 */
@Tag(name = "记忆管理", description = "用户长短期记忆的查询与删除接口")
@RestController
@RequestMapping("/api/v1/memory")
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(MemoryV1Controller.class);

    @Autowired
    private MemoryManager memoryManager;

    /**
     * 获取用户所有记忆面板数据（按类型分组）
     */
    @Operation(summary = "获取记忆面板", description = "按类型分组返回当前用户的所有记忆数据")
    @GetMapping("/panel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMemoryPanel() {
        String userId = CurrentUser.getId();
        logger.info("获取记忆面板 - userId={}", userId);

        if (userId == null || userId.isEmpty()) {
            throw new InvalidInputException("userId is required");
        }

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

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 删除单条记忆
     */
    @Operation(summary = "删除单条记忆", description = "根据记忆ID删除指定记忆")
    @DeleteMapping("/{memoryId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteMemory(
            @PathVariable("memoryId") String memoryId) {
        // 变更类操作要求已认证身份，避免未认证调用者操作匿名共享记忆桶
        String userId = CurrentUser.getRequiredId();
        logger.info("删除记忆 - userId={}, memoryId={}", userId, memoryId);

        try {
            boolean success = memoryManager.deleteMemory(userId, memoryId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", success);
            response.put("message", success ? "记忆已删除" : "删除失败");

            if (!success) {
                throw new ResourceNotFoundException("记忆", memoryId);
            }

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (BizException e) {
            throw e;   // 保留 401/404 等业务语义，不被下面的兜底转成 503
        } catch (Exception e) {
            throw new ServiceUnavailableException("记忆删除", e.getMessage());
        }
    }

    /**
     * 清空用户所有记忆
     */
    @Operation(summary = "清空全部记忆", description = "删除当前用户的所有记忆数据")
    @DeleteMapping("/clear")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearMemories() {
        // 变更类操作要求已认证身份，避免未认证调用者清空匿名共享记忆桶
        String userId = CurrentUser.getRequiredId();
        logger.info("清空记忆 - userId={}", userId);

        try {
            long deleted = memoryManager.deleteAllMemories(userId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "已清空全部记忆");
            response.put("deletedCount", deleted);

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (BizException e) {
            throw e;   // 保留 401 等业务语义，不被下面的兜底转成 503
        } catch (Exception e) {
            throw new ServiceUnavailableException("清空记忆", e.getMessage());
        }
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
