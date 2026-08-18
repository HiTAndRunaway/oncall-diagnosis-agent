package org.example.controller.v1;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.example.config.FileUploadConfig;
import org.example.dto.ApiResponse;
import org.example.dto.FileUploadRes;
import org.example.exception.InvalidInputException;
import org.example.exception.RateLimitExceededException;
import org.example.exception.ServiceUnavailableException;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 文件上传 V1 控制器
 * 提供文件上传与向量化索引接口
 */
@Tag(name = "文件上传", description = "文件上传、向量化索引与失败重索引接口")
@RestController
@RequestMapping("/api/v1")
public class UploadV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(UploadV1Controller.class);

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Operation(summary = "上传文件", description = "上传文件并自动创建向量索引，支持覆盖更新")
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<FileUploadRes>> upload(@RequestParam("file") MultipartFile file,
                                                              HttpServletRequest request) {
        if (file.isEmpty()) {
            throw new InvalidInputException("文件不能为空");
        }

        // IP 级限流检查
        String clientIp = getClientIp(request);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("file-upload", clientIp);
        if (!rateLimiter.acquirePermission()) {
            logger.warn("上传限流触发，IP: {}", clientIp);
            throw new RateLimitExceededException("上传过于频繁，请 1 分钟后再试");
        }

        // 业务层文件大小校验
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidInputException("文件大小不能超过 20MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new InvalidInputException("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            throw new InvalidInputException("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

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
                throw new ServiceUnavailableException("向量索引", e.getMessage());
            }

            FileUploadRes response = new FileUploadRes(
                    originalFilename,
                    filePath.toString(),
                    file.getSize()
            );

            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (IOException e) {
            throw new ServiceUnavailableException("文件存储", e.getMessage());
        }
    }

    /**
     * 删除文档端点
     * 删除 uploads 目录下的源文件及其在 Milvus 中的全部向量分片
     */
    @Operation(summary = "删除文档", description = "按文件名删除源文件及其向量索引（数据生命周期清理）")
    @DeleteMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteDocument(@RequestParam("filename") String filename) {
        if (filename == null || filename.isBlank()) {
            throw new InvalidInputException("文件名不能为空");
        }

        VectorIndexService.DeleteResult result = vectorIndexService.deleteDocument(filename);
        logger.info("删除文档完成: filename={}, fileDeleted={}, deletedChunks={}",
                result.filename, result.fileDeleted, result.deletedChunks);

        return ResponseEntity.ok(ApiResponse.success(result.toMap()));
    }

    /**
     * 重索引失败文档端点
     */
    @Operation(summary = "重索引失败文档", description = "查询并重新向量化所有需要重索引的文档")
    @PostMapping("/upload/reindex-failed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reindexFailed() {
        VectorIndexService.ReindexResult result = vectorIndexService.reindexFailedDocuments();
        return ResponseEntity.ok(ApiResponse.success(result.toMap()));
    }

    /**
     * 获取客户端真实 IP
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
