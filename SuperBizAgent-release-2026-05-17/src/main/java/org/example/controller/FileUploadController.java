package org.example.controller;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.example.config.FileUploadConfig;
import org.example.constant.MilvusConstants;
import org.example.dto.FileUploadRes;
import org.example.service.VectorEmbeddingService;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        // IP 级限流检查
        String clientIp = getClientIp(request);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("file-upload", clientIp);
        if (!rateLimiter.acquirePermission()) {
            logger.warn("上传限流触发，IP: {}", clientIp);
            return ResponseEntity.status(429)
                    .body(Map.of("error", "上传过于频繁，请 1 分钟后再试"));
        }

        // 业务层文件大小校验
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文件大小不能超过 20MB"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 使用原始文件名，而不是UUID，以便实现基于文件名的去重
            Path filePath = uploadDir.resolve(originalFilename).normalize();
            
            // 如果文件已存在，先删除旧文件（实现覆盖更新）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }
            
            Files.copy(file.getInputStream(), filePath);

            logger.info("文件上传成功: {}", filePath);

            // 文件上传成功后，自动调用向量索引服务
            try {
                logger.info("开始为上传文件创建向量索引: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString());
                logger.info("向量索引创建成功: {}", filePath);
            } catch (Exception e) {
                logger.error("向量索引创建失败: {}, 错误: {}", filePath, e.getMessage(), e);
                // 注意：即使索引失败，文件上传仍然成功，只是记录错误日志
                // 可以根据业务需求决定是否要删除文件或返回错误
            }

            FileUploadRes response = new FileUploadRes(
                    originalFilename,
                    filePath.toString(),
                    file.getSize()
            );

            // 使用统一的API响应格式
            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            apiResponse.setData(response);
            
            return ResponseEntity.ok(apiResponse);

        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    /**
     * 重索引失败文档端点
     * 查询 Milvus 中标记为 needsReindex=true 的文档，逐条重新向量化
     */
    @PostMapping("/api/upload/reindex-failed")
    public ResponseEntity<?> reindexFailed() {
        logger.info("开始重索引失败文档...");

        try {
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr("metadata[\"needsReindex\"] == true")
                    .withOutFields(Arrays.asList("id", "content", "vector"))
                    .build();

            R<QueryResults> queryResponse = milvusClient.query(queryParam);

            if (queryResponse.getStatus() != 0) {
                logger.error("查询 needsReindex 文档失败: {}", queryResponse.getMessage());
                return ResponseEntity.status(500)
                        .body(Map.of("error", "查询失败: " + queryResponse.getMessage()));
            }

            QueryResultsWrapper wrapper = new QueryResultsWrapper(queryResponse.getData());
            List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();

            if (records.isEmpty()) {
                logger.info("没有需要重索引的文档");
                return ResponseEntity.ok(Map.of("total", 0, "success", 0, "failed", 0));
            }

            int total = records.size();
            int success = 0;
            int failed = 0;
            List<String> errors = new ArrayList<>();

            logger.info("找到 {} 个需要重索引的文档", total);

            for (QueryResultsWrapper.RowRecord record : records) {
                String id = null;
                String content = null;
                try {
                    id = String.valueOf(record.get("id"));
                    content = String.valueOf(record.get("content"));

                    if (content == null || content.isEmpty() || "null".equals(content)) {
                        logger.warn("文档 {} 内容为空，跳过", id);
                        failed++;
                        errors.add("文档 " + id + ": 内容为空");
                        continue;
                    }

                    List<Float> newVector = embeddingService.generateEmbedding(content);
                    logger.info("文档 {} 重新向量化成功，维度: {}", id, newVector.size());

                    Map<String, Object> metadata = new HashMap<>();
                    Object originalMetaObj = record.get("metadata");
                    if (originalMetaObj != null) {
                        try {
                            com.google.gson.Gson gson = new com.google.gson.Gson();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> originalMeta = gson.fromJson(
                                    String.valueOf(originalMetaObj), Map.class);
                            if (originalMeta != null) {
                                metadata.putAll(originalMeta);
                            }
                        } catch (Exception e) {
                            logger.warn("解析 metadata 失败: {}", e.getMessage());
                        }
                    }
                    metadata.put("needsReindex", false);

                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();

                    List<UpsertParam.Field> fields = new ArrayList<>();
                    fields.add(new UpsertParam.Field("id", Collections.singletonList(id)));
                    fields.add(new UpsertParam.Field("content", Collections.singletonList(content)));
                    fields.add(new UpsertParam.Field("vector", Collections.singletonList(newVector)));
                    fields.add(new UpsertParam.Field("metadata", Collections.singletonList(metadataJson)));

                    UpsertParam upsertParam = UpsertParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .withFields(fields)
                            .build();

                    R<io.milvus.grpc.MutationResult> upsertResponse = milvusClient.upsert(upsertParam);

                    if (upsertResponse.getStatus() != 0) {
                        throw new RuntimeException("Upsert 失败: " + upsertResponse.getMessage());
                    }

                    success++;
                    logger.info("文档 {} 重索引成功 ({}/{})", id, success + failed, total);

                } catch (Exception e) {
                    failed++;
                    errors.add("文档 " + (id != null ? id : "unknown") + ": " + e.getMessage());
                    logger.error("重索引文档失败: {}", e.getMessage(), e);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("success", success);
            result.put("failed", failed);
            if (!errors.isEmpty()) {
                result.put("errors", errors);
            }

            logger.info("重索引完成: total={}, success={}, failed={}", total, success, failed);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("重索引端点异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "重索引失败: " + e.getMessage()));
        }
    }

    /**
     * 获取客户端真实 IP
     * 优先级: X-Forwarded-For > X-Real-IP > RemoteAddr
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * 统一 API 响应格式
     */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
