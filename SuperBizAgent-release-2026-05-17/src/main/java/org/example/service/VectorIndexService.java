package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.service.chunk.ChunkStrategyFactory;
import org.example.service.chunk.DocumentChunkStrategy;
import org.example.service.parser.DocumentParseException;
import org.example.service.parser.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 * 通过 DocumentParser 策略模式支持多种文件格式
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final Map<String, DocumentParser> parserMap;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 构造函数注入所有依赖和 DocumentParser 实现
     * Spring 自动收集所有实现了 DocumentParser 接口的 Bean
     */
    public VectorIndexService(MilvusServiceClient milvusClient,
                              VectorEmbeddingService embeddingService,
                              ChunkStrategyFactory chunkStrategyFactory,
                              List<DocumentParser> parsers) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.parserMap = new HashMap<>();
        for (DocumentParser parser : parsers) {
            for (String ext : parser.supportedExtensions()) {
                parserMap.put(ext.toLowerCase(), parser);
            }
        }
        logger.info("已注册 {} 个文档解析器, 支持扩展名: {}", parsers.size(), parserMap.keySet());
    }

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) ->
                parserMap.keySet().stream().anyMatch(ext -> name.toLowerCase().endsWith("." + ext))
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 1. 根据扩展名选择 Parser 读取文件内容
        String fileName = path.getFileName().toString();
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex + 1).toLowerCase();
        }

        DocumentParser parser = parserMap.get(extension);
        if (parser == null) {
            throw new DocumentParseException("不支持的文件格式: ." + extension);
        }

        String content = parser.parse(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        // 2. 归一化源路径，读取现有版本号（用于记账），随后删除该文件的旧数据（如果存在）
        String normalizedSource = normalizeSource(path.toString());
        int version = readExistingVersion(normalizedSource) + 1;
        deleteBySource(normalizedSource);

        // 3. 文档分片（通过策略工厂选择策略）
        DocumentChunkStrategy strategy = chunkStrategyFactory.getStrategy(extension);
        logger.info("使用策略 '{}' 切分文件: {}", strategy.strategyName(), filePath);
        List<DocumentChunk> chunks = strategy.chunk(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        // 4. 为每个分片生成向量并插入 Milvus
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            
            try {
                // 生成向量
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());

                // 检测零向量（embedding 断路器降级标记）
                boolean needsReindex = vector.stream().allMatch(v -> v == 0.0f);

                // 构建元数据（包含文件信息与版本记账）
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size(), version);

                // 标记需要重新索引（embedding 降级时使用零向量）
                if (needsReindex) {
                    metadata.put("needsReindex", true);
                    logger.warn("Embedding 降级：分片 {}/{} 使用零向量，已标记 needsReindex=true",
                            chunk.getChunkIndex(), chunks.size());
                }

                // 插入到 Milvus
                insertToMilvus(chunk.getContent(), vector, metadata, chunk.getChunkIndex());
                
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    /**
     * 重索引所有标记了 needsReindex=true 的文档
     * 查询 Milvus → 逐条重新向量化 → Upsert 回 Milvus
     *
     * @return 重索引结果（total/success/failed/errors）
     */
    public ReindexResult reindexFailedDocuments() {
        logger.info("开始重索引失败文档...");
        ReindexResult result = new ReindexResult();

        try {
            // 1. 查询 needsReindex=true 的文档
            io.milvus.param.dml.QueryParam queryParam = io.milvus.param.dml.QueryParam.newBuilder()
                    .withCollectionName(org.example.constant.MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr("metadata[\"needsReindex\"] == true")
                    .withOutFields(java.util.Arrays.asList("id", "content", "vector", "metadata"))
                    .build();

            io.milvus.param.R<io.milvus.grpc.QueryResults> queryResponse = milvusClient.query(queryParam);

            if (queryResponse.getStatus() != 0) {
                throw new RuntimeException("查询 needsReindex 文档失败: " + queryResponse.getMessage());
            }

            io.milvus.response.QueryResultsWrapper wrapper =
                    new io.milvus.response.QueryResultsWrapper(queryResponse.getData());
            java.util.List<io.milvus.response.QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();

            result.total = records.size();

            if (records.isEmpty()) {
                logger.info("没有需要重索引的文档");
                return result;
            }

            logger.info("找到 {} 个需要重索引的文档", result.total);

            // 2. 逐条重新向量化并更新
            for (io.milvus.response.QueryResultsWrapper.RowRecord record : records) {
                String id = null;
                String content = null;
                try {
                    id = String.valueOf(record.get("id"));
                    content = String.valueOf(record.get("content"));

                    if (content == null || content.isEmpty() || "null".equals(content)) {
                        logger.warn("文档 {} 内容为空，跳过", id);
                        result.failed++;
                        result.errors.add("文档 " + id + ": 内容为空");
                        continue;
                    }

                    java.util.List<Float> newVector = embeddingService.generateEmbedding(content);
                    logger.info("文档 {} 重新向量化成功，维度: {}", id, newVector.size());

                    // 构建 metadata（保持原有 metadata，更新 needsReindex=false）
                    java.util.Map<String, Object> metadata = new java.util.HashMap<>();
                    Object originalMetaObj = record.get("metadata");
                    if (originalMetaObj != null) {
                        try {
                            com.google.gson.Gson gson = new com.google.gson.Gson();
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> originalMeta = gson.fromJson(
                                    String.valueOf(originalMetaObj), java.util.Map.class);
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

                    // 3. Upsert 回 Milvus
                    java.util.List<io.milvus.param.dml.UpsertParam.Field> fields = new java.util.ArrayList<>();
                    fields.add(new io.milvus.param.dml.UpsertParam.Field("id",
                            java.util.Collections.singletonList(id)));
                    fields.add(new io.milvus.param.dml.UpsertParam.Field(
                            org.example.constant.MilvusConstants.TENANT_ID_FIELD,
                            java.util.Collections.singletonList(org.example.constant.MilvusConstants.DEFAULT_TENANT_ID)));
                    fields.add(new io.milvus.param.dml.UpsertParam.Field("content",
                            java.util.Collections.singletonList(content)));
                    fields.add(new io.milvus.param.dml.UpsertParam.Field("vector",
                            java.util.Collections.singletonList(newVector)));
                    fields.add(new io.milvus.param.dml.UpsertParam.Field("metadata",
                            java.util.Collections.singletonList(metadataJson)));

                    io.milvus.param.dml.UpsertParam upsertParam = io.milvus.param.dml.UpsertParam.newBuilder()
                            .withCollectionName(org.example.constant.MilvusConstants.MILVUS_COLLECTION_NAME)
                            .withFields(fields)
                            .build();

                    io.milvus.param.R<io.milvus.grpc.MutationResult> upsertResponse = milvusClient.upsert(upsertParam);

                    if (upsertResponse.getStatus() != 0) {
                        throw new RuntimeException("Upsert 失败: " + upsertResponse.getMessage());
                    }

                    result.success++;
                    logger.info("文档 {} 重索引成功 ({}/{})", id, result.success + result.failed, result.total);

                } catch (Exception e) {
                    result.failed++;
                    result.errors.add("文档 " + (id != null ? id : "unknown") + ": " + e.getMessage());
                    logger.error("重索引文档失败: {}", e.getMessage(), e);
                }
            }

            logger.info("重索引完成: total={}, success={}, failed={}", result.total, result.success, result.failed);
            return result;

        } catch (Exception e) {
            logger.error("重索引流程异常", e);
            throw new RuntimeException("重索引失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重索引结果
     */
    public static class ReindexResult {
        public int total;
        public int success;
        public int failed;
        public java.util.List<String> errors = new java.util.ArrayList<>();

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("total", total);
            map.put("success", success);
            map.put("failed", failed);
            if (!errors.isEmpty()) {
                map.put("errors", errors);
            }
            return map;
        }
    }

    /**
     * 删除文档结果
     */
    public static class DeleteResult {
        public String filename;
        public boolean fileDeleted;
        public long deletedChunks;

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("filename", filename);
            map.put("fileDeleted", fileDeleted);
            map.put("deletedChunks", deletedChunks);
            return map;
        }
    }

    /**
     * 按 _source 删除 Milvus 中的旧数据（物理删除，供覆盖更新与文档删除共用）
     *
     * @param normalizedSource 归一化后的源文件路径（正斜杠分隔）
     * @return 删除的记录数（首次索引或异常时返回 0）
     */
    public long deleteBySource(String normalizedSource) {
        try {
            // 构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedSource);

            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedSource, expr);

            // 确保 collection 已加载（删除操作需要集合已加载）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            // 状态码 65535 表示集合已经加载，这不是错误
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
                return 0;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
                return 0;
            }

            long deletedCount = response.getData().getDeleteCnt();
            logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedSource, deletedCount);
            return deletedCount;

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 删除指定文档及其在 Milvus 中的分片（数据生命周期"删"这一环）
     * <p>
     * 仅取文件名的 basename，防止路径穿越；删除 uploads 目录下的源文件与对应的 Milvus 分片。
     *
     * @param filename 文件名（不含路径）
     * @return 删除结果
     */
    public DeleteResult deleteDocument(String filename) {
        // 仅保留 basename，防止路径穿越
        Path safeName = Paths.get(filename).getFileName();
        String baseName = safeName != null ? safeName.toString() : "";
        if (baseName.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        DeleteResult result = new DeleteResult();
        result.filename = baseName;

        // 删除源文件（若存在）
        Path uploadDir = Paths.get(uploadPath).normalize();
        Path filePath = uploadDir.resolve(baseName).normalize();
        if (Files.exists(filePath)) {
            try {
                Files.delete(filePath);
                result.fileDeleted = true;
                logger.info("已删除源文件: {}", filePath);
            } catch (Exception e) {
                logger.warn("删除源文件失败: {}", filePath, e);
            }
        }

        // 删除 Milvus 分片
        String normalizedSource = normalizeSource(filePath.toString());
        result.deletedChunks = deleteBySource(normalizedSource);

        return result;
    }

    /**
     * 查询指定 _source 现有分片的最大 version（无则返回 0），用于版本记账。
     */
    private int readExistingVersion(String normalizedSource) {
        try {
            // 确保 collection 已加载（query 需要集合处于 loaded 状态，否则版本记账会退化为 1）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败，版本记账退化为 1: {}", loadResponse.getMessage());
                return 0;
            }

            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedSource);
            io.milvus.param.dml.QueryParam queryParam = io.milvus.param.dml.QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .withOutFields(java.util.List.of("metadata"))
                    .build();

            io.milvus.param.R<io.milvus.grpc.QueryResults> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0) {
                logger.warn("查询现有版本失败，默认从 1 开始: {}", response.getMessage());
                return 0;
            }

            io.milvus.response.QueryResultsWrapper wrapper =
                    new io.milvus.response.QueryResultsWrapper(response.getData());
            com.google.gson.Gson gson = new com.google.gson.Gson();
            int maxVersion = 0;
            for (io.milvus.response.QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                Object metaObj = record.get("metadata");
                if (metaObj == null) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> meta = gson.fromJson(
                            String.valueOf(metaObj), java.util.Map.class);
                    Object version = meta.get("version");
                    if (version instanceof Number) {
                        maxVersion = Math.max(maxVersion, ((Number) version).intValue());
                    }
                } catch (Exception e) {
                    logger.warn("解析 metadata 版本失败: {}", e.getMessage());
                }
            }
            return maxVersion;

        } catch (Exception e) {
            logger.warn("查询现有版本异常，默认从 1 开始: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 将文件路径归一化为统一的正斜杠分隔形式，用于 Milvus 中的 _source 存储与匹配。
     */
    private String normalizeSource(String filePath) {
        Path path = Paths.get(filePath).normalize();
        return path.toString().replace(File.separator, "/");
    }

    /**
     * 构建元数据（包含文件信息与版本记账）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks, int version) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);

        // 版本记账（硬删语义下记录"当前线上是第几版、何时灌入"）
        metadata.put("version", version);
        metadata.put("uploadedAt", System.currentTimeMillis());

        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }

        // 合并策略附加的扩展元数据（如 parent-child 的 parentId、parentContent 等）
        if (chunk.getExtraMetadata() != null) {
            metadata.putAll(chunk.getExtraMetadata());
        }

        return metadata;
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertToMilvus(String content, List<Float> vector, 
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            // 生成唯一 ID（使用 _source + 分片索引）
            String source = (String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

            // 构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();

            // ID 字段
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));

            // 租户字段（Partition Key，多租户预留）
            fields.add(new InsertParam.Field(MilvusConstants.TENANT_ID_FIELD,
                    Collections.singletonList(MilvusConstants.DEFAULT_TENANT_ID)));

            // content 字段
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            
            // vector 字段
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            // metadata 字段（JSON 对象）
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
