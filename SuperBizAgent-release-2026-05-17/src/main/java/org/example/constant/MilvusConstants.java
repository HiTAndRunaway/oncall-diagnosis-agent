package org.example.constant;

public class MilvusConstants {
    
    /**
     * Milvus 数据库名称
     */
    public static final String MILVUS_DB_NAME = "default";
    
    /**
     * Milvus 集合名称
     */
    public static final String MILVUS_COLLECTION_NAME = "biz";
    
    /**
     * 向量维度（豆包 embedding 模型的维度）
     */
    public static final int VECTOR_DIM = 1024;  // 豆包模型返回1024维向量
    
    /**
     * ID字段最大长度
     */
    public static final int ID_MAX_LENGTH = 256;
    
    /**
     * Content字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;
    
    /**
     * 默认分片数
     */
    public static final int DEFAULT_SHARD_NUMBER = 2;
    
    /**
     * Sparse vector 字段名称（BM25 稀疏向量）
     */
    public static final String SPARSE_VECTOR_FIELD = "sparse_vector";

    /**
     * BM25 分词器名称
     */
    public static final String ANALYZER_NAME = "chinese_analyzer";

    /**
     * BM25 Function 名称
     */
    public static final String BM25_FUNCTION_NAME = "bm25_func";

    /**
     * BM25 Function 描述
     */
    public static final String BM25_FUNCTION_DESC = "BM25 function for content field";

    private MilvusConstants() {
        // 工具类，禁止实例化
    }
}
