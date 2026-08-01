package org.example.controller.v1;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.ShowCollectionsParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查 V1 控制器
 * 提供 Milvus 连接检查和综合健康状态接口
 */
@Tag(name = "健康检查", description = "服务健康状态与依赖连通性检查")
@RestController
@RequestMapping("/api/v1")
public class HealthV1Controller {

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * Milvus 健康检查
     */
    @Operation(summary = "Milvus 健康检查", description = "检查 Milvus 向量数据库连接状态和集合列表")
    @GetMapping("/milvus/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> milvusHealth() {
        Map<String, Object> result = new HashMap<>();

        try {
            R<ShowCollectionsResponse> response = milvusClient.showCollections(
                ShowCollectionsParam.newBuilder().build()
            );

            if (response.getStatus() == 0) {
                result.put("message", "ok");
                result.put("collections", response.getData().getCollectionNamesList());
                return ResponseEntity.ok(ApiResponse.success(result));
            } else {
                result.put("message", response.getMessage());
                return ResponseEntity.status(503).body(ApiResponse.error(503, response.getMessage()));
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(503).body(ApiResponse.error(503, e.getMessage()));
        }
    }

    /**
     * 综合健康检查
     * 检查 Milvus、Redis 等核心依赖的连通性
     */
    @Operation(summary = "综合健康检查", description = "检查应用所有核心依赖（Milvus、Redis）的连通状态")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> result = new HashMap<>();
        boolean allHealthy = true;

        // 检查 Milvus
        Map<String, Object> milvusStatus = new HashMap<>();
        try {
            R<ShowCollectionsResponse> response = milvusClient.showCollections(
                ShowCollectionsParam.newBuilder().build()
            );
            milvusStatus.put("status", response.getStatus() == 0 ? "UP" : "DOWN");
            milvusStatus.put("message", response.getStatus() == 0 ? "ok" : response.getMessage());
        } catch (Exception e) {
            milvusStatus.put("status", "DOWN");
            milvusStatus.put("message", e.getMessage());
            allHealthy = false;
        }
        result.put("milvus", milvusStatus);

        // 检查 Redis
        Map<String, Object> redisStatus = new HashMap<>();
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().get("health-check");
                redisStatus.put("status", "UP");
                redisStatus.put("message", "ok");
            } catch (Exception e) {
                redisStatus.put("status", "DOWN");
                redisStatus.put("message", e.getMessage());
                allHealthy = false;
            }
        } else {
            redisStatus.put("status", "NOT_CONFIGURED");
            redisStatus.put("message", "Redis 未配置");
        }
        result.put("redis", redisStatus);

        result.put("status", allHealthy ? "UP" : "DEGRADED");

        if (allHealthy) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.status(503).body(ApiResponse.error(503, "部分依赖不可用"));
        }
    }
}
