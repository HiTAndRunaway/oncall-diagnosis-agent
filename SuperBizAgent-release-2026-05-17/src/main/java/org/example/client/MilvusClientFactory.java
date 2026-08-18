package org.example.client;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Milvus 客户端工厂类
 * 负责创建和初始化 Milvus 客户端连接
 */
@Component
public class MilvusClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);

    /**
     * Milvus RESTful API 端口（v2.5.x standalone 模式下 HTTP 代理端口）
     */
    private static final int MILVUS_HTTP_PORT = 9091;

    @Autowired
    private MilvusProperties milvusProperties;

    /**
     * 创建并初始化 Milvus 客户端
     * 
     * 简化版本：直接连接并创建 collection
     * 
     * @return MilvusServiceClient 实例
     * @throws RuntimeException 如果连接或初始化失败
     */
    public MilvusServiceClient createClient() {
        MilvusServiceClient client = null;

        try {
            // 1. 连接到 Milvus
            logger.info("正在连接到 Milvus: {}:{}", milvusProperties.getHost(), milvusProperties.getPort());
            client = connectToMilvus();
            logger.info("成功连接到 Milvus");

            // 2. 检查并初始化 biz collection（缺失则创建；schema 不含 tenant_id 则自动重建）
            boolean needInitBiz = !collectionExists(client, MilvusConstants.MILVUS_COLLECTION_NAME);
            if (!needInitBiz && !schemaHasTenantId(client)) {
                logger.warn("collection '{}' 缺少 tenant_id 字段（旧 schema），自动重建...",
                        MilvusConstants.MILVUS_COLLECTION_NAME);
                dropCollection(client, MilvusConstants.MILVUS_COLLECTION_NAME);
                logger.info("已删除旧 collection，准备重建（数据需重新 make upload）");
                needInitBiz = true;
            }

            if (needInitBiz) {
                logger.info("正在创建 collection '{}' ...", MilvusConstants.MILVUS_COLLECTION_NAME);
                createBizCollection(client);
                logger.info("成功创建 collection '{}'", MilvusConstants.MILVUS_COLLECTION_NAME);

                // 创建索引
                createIndexes(client);
                logger.info("成功创建索引");

                // 创建 BM25 所需组件
                createAnalyzer(client);
                createBm25Function(client);
                createSparseIndex(client);
                logger.info("BM25 组件初始化完成");
            } else {
                logger.info("collection '{}' 已存在且 schema 正确", MilvusConstants.MILVUS_COLLECTION_NAME);
            }

            // 3. 检查并创建 user_memory collection（如果不存在）
            if (!collectionExists(client, MilvusConstants.MEMORY_COLLECTION_NAME)) {
                logger.info("collection '{}' 不存在，正在创建...", MilvusConstants.MEMORY_COLLECTION_NAME);
                createUserMemoryCollection(client);
                logger.info("成功创建 collection '{}'", MilvusConstants.MEMORY_COLLECTION_NAME);
                createUserMemoryIndexes(client);
                logger.info("成功创建 user_memory 索引");
            } else {
                logger.info("collection '{}' 已存在", MilvusConstants.MEMORY_COLLECTION_NAME);
            }

            return client;

        } catch (Exception e) {
            logger.error("创建 Milvus 客户端失败", e);
            if (client != null) {
                client.close();
            }
            throw new RuntimeException("创建 Milvus 客户端失败: " + e.getMessage(), e);
        }
    }

    /**
     * 连接到 Milvus
     */
    private MilvusServiceClient connectToMilvus() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(milvusProperties.getHost())
                .withPort(milvusProperties.getPort())
                .withConnectTimeout(milvusProperties.getTimeout(), TimeUnit.MILLISECONDS);

        // 如果配置了用户名和密码
        if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
            builder.withAuthorization(milvusProperties.getUsername(), milvusProperties.getPassword());
        }

        return new MilvusServiceClient(builder.build());
    }

    /**
     * 检查 collection 是否存在
     */
    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        if (response.getStatus() != 0) {
            throw new RuntimeException("检查 collection 失败: " + response.getMessage());
        }

        return response.getData();
    }

    /**
     * 检查 biz collection 的 schema 是否已包含 tenant_id 字段
     * <p>
     * 用于多租户预留的自动迁移：旧 collection 缺少 tenant_id 时触发重建。
     * 无法判定（describe 失败）时抛异常而非返回 false，避免误删正常 collection。
     */
    private boolean schemaHasTenantId(MilvusServiceClient client) {
        R<io.milvus.grpc.DescribeCollectionResponse> response = client.describeCollection(
                DescribeCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build());

        if (response.getStatus() != 0) {
            throw new RuntimeException("检查 collection schema 失败: " + response.getMessage());
        }

        for (io.milvus.grpc.FieldSchema field : response.getData().getSchema().getFieldsList()) {
            if (MilvusConstants.TENANT_ID_FIELD.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 删除 collection（用于 schema 变更时的重建）
     */
    private void dropCollection(MilvusServiceClient client, String collectionName) {
        R<RpcStatus> response = client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        if (response.getStatus() != 0) {
            throw new RuntimeException("删除 collection 失败: " + response.getMessage());
        }
    }

    /**
     * 创建 biz collection
     */
    private void createBizCollection(MilvusServiceClient client) {
        // 定义字段
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        // 租户 ID 字段（Partition Key，多租户预留）
        FieldType tenantIdField = FieldType.newBuilder()
                .withName(MilvusConstants.TENANT_ID_FIELD)
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.TENANT_ID_MAX_LENGTH)
                .withPartitionKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)  // 改为 FloatVector
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        FieldType sparseVectorField = FieldType.newBuilder()
                .withName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withDataType(DataType.SparseFloatVector)
                .build();

        // 创建 collection schema
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(tenantIdField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .addFieldType(sparseVectorField)
                .build();

        // 创建 collection
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDescription("Business knowledge collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 collection 创建索引
     */
    private void createIndexes(MilvusServiceClient client) {
        // 为 vector 字段创建索引（FloatVector 使用 IVF_FLAT 和 L2 距离）
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)  // L2 距离（欧氏距离）
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + response.getMessage());
        }
        
        logger.info("成功为 vector 字段创建索引");
    }

    /**
     * 创建中文分词器（用于 BM25）
     */
    private void createAnalyzer(MilvusServiceClient client) {
        // Milvus 2.4+ 通过 RESTful API 创建 Analyzer
        // HTTP 代理端口为 9091（独立于 gRPC 端口 19530）
        String restUrl = String.format("http://%s:%d/v2/analyzers",
                milvusProperties.getHost(), MILVUS_HTTP_PORT);

        RestTemplate rest = new RestTemplate();

        Map<String, Object> analyzerBody = new HashMap<>();
        analyzerBody.put("name", MilvusConstants.ANALYZER_NAME);
        analyzerBody.put("type", "chinese");
        analyzerBody.put("params", Map.of());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (milvusProperties.getUsername() != null
                    && !milvusProperties.getUsername().isEmpty()) {
                String auth = milvusProperties.getUsername() + ":"
                        + milvusProperties.getPassword();
                headers.setBasicAuth(
                        Base64.getEncoder().encodeToString(auth.getBytes()));
            }

            HttpEntity<Map<String, Object>> request =
                    new HttpEntity<>(analyzerBody, headers);

            rest.postForEntity(restUrl, request, String.class);
            logger.info("成功创建 Analyzer: {}", MilvusConstants.ANALYZER_NAME);
        } catch (Exception e) {
            // Analyzer 可能已存在，仅记录日志
            logger.info("创建 Analyzer 时出现异常（可能已存在）: {}", e.getMessage());
        }
    }

    /**
     * 创建 BM25 Function，将 content 自动转换为 sparse_vector
     */
    private void createBm25Function(MilvusServiceClient client) {
        String restUrl = String.format("http://%s:%d/v2/functions",
                milvusProperties.getHost(), MILVUS_HTTP_PORT);

        RestTemplate rest = new RestTemplate();

        Map<String, Object> functionBody = new HashMap<>();
        functionBody.put("name", MilvusConstants.BM25_FUNCTION_NAME);
        functionBody.put("description", MilvusConstants.BM25_FUNCTION_DESC);
        functionBody.put("type", "BM25");
        functionBody.put("params", Map.of(
                "input_field", "content",
                "output_field", MilvusConstants.SPARSE_VECTOR_FIELD,
                "analyzer", MilvusConstants.ANALYZER_NAME
        ));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (milvusProperties.getUsername() != null
                    && !milvusProperties.getUsername().isEmpty()) {
                String auth = milvusProperties.getUsername() + ":"
                        + milvusProperties.getPassword();
                headers.setBasicAuth(
                        Base64.getEncoder().encodeToString(auth.getBytes()));
            }

            HttpEntity<Map<String, Object>> request =
                    new HttpEntity<>(functionBody, headers);

            ResponseEntity<String> response =
                    rest.postForEntity(restUrl, request, String.class);
            logger.info("成功创建 BM25 Function: {} (HTTP {})",
                    MilvusConstants.BM25_FUNCTION_NAME, response.getStatusCode().value());
        } catch (Exception e) {
            // Function 可能已存在，仅记录日志
            logger.info("创建 BM25 Function 时出现异常（可能已存在）: {}", e.getMessage());
        }
    }

    /**
     * 创建 user_memory collection（用户长期记忆）
     * 无 BM25/稀疏向量，仅 dense vector + userId 过滤
     */
    private void createUserMemoryCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType userIdField = FieldType.newBuilder()
                .withName("user_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(128)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.MEMORY_CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(userIdField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withDescription("User long-term memory collection")
                .withSchema(schema)
                .withShardsNum(1)  // 记忆数据量小，单分片即可
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 user_memory collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 user_memory collection 创建索引
     * - vector: IVF_FLAT + L2
     * - user_id: 标量索引（用于过滤查询）
     */
    private void createUserMemoryIndexes(MilvusServiceClient client) {
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 user_memory vector 索引失败: " + response.getMessage());
        }

        // 为 user_id 创建标量索引（用于 expr 过滤）
        // Milvus 2.5+ 支持对 VarChar 字段建 INVERTED 索引以加速过滤
        CreateIndexParam scalarIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withFieldName("user_id")
                .withIndexType(IndexType.INVERTED)
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> scalarRsp = client.createIndex(scalarIndexParam);
        if (scalarRsp.getStatus() != 0) {
            logger.warn("创建 user_id scalar 索引时出现警告（非致命）: {}", scalarRsp.getMessage());
        }

        logger.info("成功为 user_memory 字段创建索引");
    }

    /**
     * 为 sparse_vector 字段创建 SPARSE_INVERTED_INDEX 索引
     */
    private void createSparseIndex(MilvusServiceClient client) {
        CreateIndexParam sparseIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName(MilvusConstants.SPARSE_VECTOR_FIELD)
                .withIndexType(IndexType.SPARSE_INVERTED_INDEX)
                .withMetricType(MetricType.IP)
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(sparseIndexParam);
        if (response.getStatus() != 0) {
            // 索引可能已存在，记录日志但不阻断
            logger.warn("创建 sparse index 时出现警告: {}", response.getMessage());
        } else {
            logger.info("成功为 sparse_vector 字段创建索引");
        }
    }
}
