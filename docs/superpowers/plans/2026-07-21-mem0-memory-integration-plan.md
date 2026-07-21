# Mem0 风格长期记忆集成 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 SuperBizAgent 对话路径中集成长期记忆系统，自动从对话中提取事实/画像/偏好，存入 Milvus，支持 Agent 按需查询和用户管理。

**Architecture:** 新增 `user_memory` Milvus collection + `MemoryManager/MemoryExtractor/MemoryDecayService` 服务层 + `RecallMemoryTool/ForgetMemoryTool` Agent 工具 + `MemoryController` REST API + 前端「我的记忆」面板。短期会话（Redis）与长期记忆（Milvus）完全分离，通过 `memory.enabled` 开关控制。

**Tech Stack:** Spring Boot 3.2, Spring AI Alibaba Agent Framework, Milvus SDK 2.6.10, DashScope (text-embedding-v4 + qwen-turbo), Redis (Spring Data Redis), Maven

## Global Constraints

- Java 17，Spring Boot 3.2，所有新增 Bean 使用 `@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")`
- `memory.enabled=false` 时完全回退，不影响现有功能
- 复用现有 `VectorEmbeddingService` 做向量化，复用 `DashScopeLlmClient` 调 LLM
- 新的 `user_memory` collection 与现有 `biz` collection 隔离
- 纯向量搜索（L2 距离），不复用 BM25/Rerank
- 异步提取使用专用 `memoryExecutor` 线程池，不复用 `summaryExecutor`
- 前端 UI 占位先做，具体风格后续由 `axi-front-design` skill 重写
- 每完成一个 Phase 需要编译测试通过

## ⚠️ 实现注意

1. **Milvus Upsert 全字段替换**：Milvus 的 `upsert` 操作会替换整行数据，不能只传 `id` + `metadata`。在 MemoryDecayService（Task 1.6）和 MemoryManager.updateMemory()（Task 2.1）中，需要先查询完整记录（id / user_id / vector / content / metadata），修改 metadata 后再 upsert 全部字段。计划中的代码片段已展示字段列表，实现时确保所有字段齐全。

2. **@Tool 注解的正确导入**：Spring AI Alibaba Agent Framework 1.1.0 的 `@Tool` 注解包路径需要核实。参考项目中已有工具类（如 `InternalDocsTools.java`）的实际 import 路径。不要盲目使用计划中的 import 语句。

3. **Phase 3 任务互依**：Task 3.1（MemoryExtractor）、3.2（SessionManager）、3.3（ChatController）、3.4（ChatService）互有依赖，建议按 3.2 → 3.3 → 3.4 → 3.1 的顺序实现，完成后统一编译通过再 commit。

---

## File Structure

### 新增文件

```
SuperBizAgent-release-2026-05-17/src/main/java/org/example/
├── config/
│   └── MemoryProperties.java           ← @ConfigurationProperties("memory")
├── service/
│   ├── MemoryManager.java              ← 记忆 CRUD，Milvus 协调读写
│   ├── MemoryExtractor.java            ← @Async 批量提取记忆（LLM + 向量化 + 冲突判断）
│   ├── MemorySearchService.java        ← 纯向量搜索（userId 过滤）
│   └── MemoryDecayService.java         ← @Scheduled 定时置信度衰减
├── agent/tool/
│   ├── RecallMemoryTool.java           ← @Tool 按需查询记忆
│   └── ForgetMemoryTool.java           ← @Tool 按指令删除记忆
└── controller/
    └── MemoryController.java           ← REST: GET /api/memory/panel, DELETE /api/memory/{id}, DELETE /api/memory/clear
```

### 修改文件

| 文件 | 变更内容 |
|------|---------|
| `config/AsyncConfig.java` | 新增 `memoryExecutor` Bean |
| `constant/MilvusConstants.java` | 新增 `MEMORY_COLLECTION_NAME`、`MEMORY_CONTENT_MAX_LENGTH` 常量 |
| `client/MilvusClientFactory.java` | 新增 `createUserMemoryCollection()` 方法 |
| `config/MilvusConfig.java` | 在 `@PostConstruct` 或 `@Bean` 中调用 `createUserMemoryCollection()` |
| `service/SessionManager.java` | `SessionMeta` 新增 `lastExtractedMessageCount`；`addMessage()` 后触发 MemoryExtractor |
| `service/ChatService.java` | `buildSystemPrompt()` 新增用户画像/偏好区块；`buildMethodToolsArray()` 新增 RecallMemoryTool / ForgetMemoryTool |
| `controller/ChatController.java` | `ChatRequest` 新增 `userId` 字段；`chat()`/`chatStream()` 传递 userId |
| `resources/application.yml` | 新增 `memory.*` 配置块 |

### 不改的文件

`RagService`、`VectorSearchService`、`VectorIndexService`、`VectorEmbeddingService`、`SummaryGenerator`、`AiOpsService`、`RedisConfig`、`QueryRewriteService`、所有现有工具类

---

## Phase 1: 配置 + Collection + 衰减基础设施

### Task 1.1: MemoryProperties 配置绑定

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/config/MemoryProperties.java`

**Interfaces:**
- Consumes: （无，首个任务）
- Produces: `MemoryProperties` bean，供 MemoryManager / MemoryExtractor / MemoryDecayService 注入

- [ ] **Step 1: 创建 MemoryProperties 类**

```java
package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    /** 全局开关，false 时所有记忆功能不注册 */
    private boolean enabled = true;

    private Extraction extraction = new Extraction();
    private Search search = new Search();
    private Decay decay = new Decay();
    private Ttl ttl = new Ttl();
    private SystemPrompt systemPrompt = new SystemPrompt();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Extraction getExtraction() { return extraction; }
    public void setExtraction(Extraction extraction) { this.extraction = extraction; }

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }

    public Decay getDecay() { return decay; }
    public void setDecay(Decay decay) { this.decay = decay; }

    public Ttl getTtl() { return ttl; }
    public void setTtl(Ttl ttl) { this.ttl = ttl; }

    public SystemPrompt getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(SystemPrompt systemPrompt) { this.systemPrompt = systemPrompt; }

    // ===== 嵌套配置类 =====

    public static class Extraction {
        private int triggerMessageCount = 6;
        private String model = "qwen-turbo";
        private int maxBatchMessages = 50;

        public int getTriggerMessageCount() { return triggerMessageCount; }
        public void setTriggerMessageCount(int triggerMessageCount) { this.triggerMessageCount = triggerMessageCount; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxBatchMessages() { return maxBatchMessages; }
        public void setMaxBatchMessages(int maxBatchMessages) { this.maxBatchMessages = maxBatchMessages; }
    }

    public static class Search {
        private int topK = 5;
        private double scoreThreshold = 0.6;

        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getScoreThreshold() { return scoreThreshold; }
        public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
    }

    public static class Decay {
        private boolean enabled = true;
        private String cron = "0 3 * * *";
        private double decayFactor = 0.1;
        private double minConfidence = 0.3;
        private int noAccessThresholdHours = 168;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public double getDecayFactor() { return decayFactor; }
        public void setDecayFactor(double decayFactor) { this.decayFactor = decayFactor; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
        public int getNoAccessThresholdHours() { return noAccessThresholdHours; }
        public void setNoAccessThresholdHours(int noAccessThresholdHours) { this.noAccessThresholdHours = noAccessThresholdHours; }
    }

    public static class Ttl {
        private int factHours = 0;
        private int profileHours = 2160;
        private int preferenceHours = 720;

        public int getFactHours() { return factHours; }
        public void setFactHours(int factHours) { this.factHours = factHours; }
        public int getProfileHours() { return profileHours; }
        public void setProfileHours(int profileHours) { this.profileHours = profileHours; }
        public int getPreferenceHours() { return preferenceHours; }
        public void setPreferenceHours(int preferenceHours) { this.preferenceHours = preferenceHours; }
    }

    public static class SystemPrompt {
        private boolean injectProfile = true;
        private boolean injectPreferences = true;
        private int maxLength = 500;

        public boolean isInjectProfile() { return injectProfile; }
        public void setInjectProfile(boolean injectProfile) { this.injectProfile = injectProfile; }
        public boolean isInjectPreferences() { return injectPreferences; }
        public void setInjectPreferences(boolean injectPreferences) { this.injectPreferences = injectPreferences; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }
}
```

- [ ] **Step 2: 添加 @EnableConfigurationProperties 到主类**

打开 `Main.java`，检查是否已有 `@EnableConfigurationProperties`，如果没有则添加：

```java
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({MemoryProperties.class})
```

- [ ] **Step 3: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/config/MemoryProperties.java
git commit -m "feat: add MemoryProperties configuration binding"
```

---

### Task 1.2: application.yml 新增 memory 配置块

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/application.yml`

**Interfaces:**
- Consumes: MemoryProperties 类定义
- Produces: 完整的 `memory.*` 配置项供 Spring Boot 绑定

- [ ] **Step 1: 在 application.yml 末尾追加 memory 配置**

```yaml
# Mem0 风格长期记忆配置
memory:
  enabled: true                          # 全局开关：false 时所有记忆功能不注册
  extraction:
    trigger-message-count: 6             # 会话新增消息对超过此数触发提取
    model: qwen-turbo                    # 提取 + 冲突判断用的轻量 LLM
    max-batch-messages: 50               # 一次提取最多分析的对话条数
  search:
    top-k: 5                             # recallMemory 默认返回数
    score-threshold: 0.6                 # 冲突检测时向量相似度最低阈值
  decay:
    enabled: true
    cron: "0 3 * * *"                    # 每天凌晨 3 点执行
    decay-factor: 0.1                    # 每次衰减的置信度减少量
    min-confidence: 0.3                  # 低于此值自动删除
    no-access-threshold-hours: 168       # 7 天无访问触发衰减
  ttl:
    fact-hours: 0                        # 0 表示永不过期
    profile-hours: 2160                  # 90 天（90 × 24）
    preference-hours: 720                # 30 天（30 × 24）
  system-prompt:
    inject-profile: true                 # 是否注入用户画像到 System Prompt
    inject-preferences: true             # 是否注入行为偏好到 System Prompt
    max-length: 500                      # 注入内容最大字符数
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/resources/application.yml
git commit -m "feat: add memory configuration block to application.yml"
```

---

### Task 1.3: AsyncConfig 新增 memoryExecutor 线程池

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/config/AsyncConfig.java`

**Interfaces:**
- Consumes: （无）
- Produces: `memoryExecutor` Bean，供 MemoryExtractor `@Async("memoryExecutor")` 使用

- [ ] **Step 1: 在 AsyncConfig 中新增 memoryExecutor Bean**

在 `AsyncConfig.java` 的 `searchExecutor()` 方法之后、类结束括号之前添加：

```java
/**
 * 记忆提取专用线程池
 * 为异步记忆提取和冲突判断提供线程资源
 */
@Bean("memoryExecutor")
public Executor memoryExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(50);
    executor.setKeepAliveSeconds(60);
    executor.setThreadNamePrefix("memory-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy() {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            logger.warn("记忆提取线程池已满，任务被拒绝: {}", e.getActiveCount());
            super.rejectedExecution(r, e);
        }
    });
    executor.initialize();
    return executor;
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/config/AsyncConfig.java
git commit -m "feat: add memoryExecutor thread pool for async memory extraction"
```

---

### Task 1.4: MilvusConstants 新增 user_memory 常量

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/constant/MilvusConstants.java`

**Interfaces:**
- Consumes: （无）
- Produces: `MEMORY_COLLECTION_NAME`、`MEMORY_CONTENT_MAX_LENGTH` 供 MilvusClientFactory 和 MemoryManager 使用

- [ ] **Step 1: 在 MilvusConstants 中新增常量**

在 `BM25_FUNCTION_DESC` 之后添加：

```java
/**
 * 用户记忆 collection 名称
 */
public static final String MEMORY_COLLECTION_NAME = "user_memory";

/**
 * 记忆内容字段最大长度
 */
public static final int MEMORY_CONTENT_MAX_LENGTH = 4096;
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/constant/MilvusConstants.java
git commit -m "feat: add MEMORY_COLLECTION_NAME constant for user_memory collection"
```

---

### Task 1.5: MilvusClientFactory 新建 user_memory collection

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/client/MilvusClientFactory.java`

**Interfaces:**
- Consumes: `MilvusConstants.MEMORY_COLLECTION_NAME`、`MilvusConstants.VECTOR_DIM`、`MilvusConstants.MEMORY_CONTENT_MAX_LENGTH`
- Produces: Milvus 中存在 `user_memory` collection（schema: id/ user_id / vector / content / metadata，索引: IVF_FLAT + L2）

- [ ] **Step 1: 在 createClient() 中追加 user_memory 创建逻辑**

在 `createClient()` 方法中，`biz` collection 创建逻辑之后、`return client;` 之前添加：

```java
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
```

- [ ] **Step 2: 新增 createUserMemoryCollection() 私有方法**

在类末尾、`createSparseIndex()` 方法之后添加：

```java
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
```

- [ ] **Step 3: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/client/MilvusClientFactory.java
git commit -m "feat: add user_memory collection creation in MilvusClientFactory"
```

---

### Task 1.6: MemoryDecayService 定时衰减

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemoryDecayService.java`

**Interfaces:**
- Consumes: MemoryProperties（配置）、`MilvusServiceClient`（查询/更新/删除）、MemoryManager（获取/更新记忆）
- Produces: 定时衰减低活跃度记忆的置信度

- [ ] **Step 1: 创建 MemoryDecayService**

> **注意**: 由于 MemoryManager 尚未实现，本任务先用 MilvusServiceClient 直连实现衰减的核心逻辑。后续 Phase 2 完成 MemoryManager 后可重构为其方法调用。

```java
package org.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import org.example.config.MemoryProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryDecayService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryDecayService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private MemoryProperties memoryProperties;

    private final Gson gson = new Gson();

    @Scheduled(cron = "${memory.decay.cron:0 3 * * *}")
    public void runDecayCycle() {
        if (!memoryProperties.getDecay().isEnabled()) {
            logger.debug("记忆衰减已禁用");
            return;
        }

        logger.info("开始记忆衰减周期");

        try {
            // 1. 确保 collection 已加载
            milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .build()
            );

            // 2. 查询所有记忆
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withOutFields(Arrays.asList("id", "metadata"))
                    .withLimit(1000L)  // 单次最多处理1000条
                    .build();

            R<QueryResultsWrapper> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0) {
                logger.warn("查询记忆失败: {}", response.getMessage());
                return;
            }

            QueryResultsWrapper wrapper = response.getData();
            List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();

            if (records.isEmpty()) {
                logger.info("无记忆需要处理");
                return;
            }

            int decayedCount = 0;
            int deletedCount = 0;
            long now = Instant.now().toEpochMilli();
            double decayFactor = memoryProperties.getDecay().getDecayFactor();
            double minConfidence = memoryProperties.getDecay().getMinConfidence();
            long noAccessThresholdMs = (long) memoryProperties.getDecay().getNoAccessThresholdHours() * 3600 * 1000;

            for (QueryResultsWrapper.RowRecord record : records) {
                String id = (String) record.get("id");
                Object metaObj = record.get("metadata");

                if (metaObj == null) continue;

                Map<String, Object> metadata;
                if (metaObj instanceof String) {
                    metadata = gson.fromJson((String) metaObj, new TypeToken<Map<String, Object>>(){}.getType());
                } else if (metaObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) metaObj;
                    metadata = m;
                } else {
                    continue;
                }

                // 检查上次访问时间
                Number lastAccessed = (Number) metadata.getOrDefault("lastAccessedAt", 0);
                if (now - lastAccessed.longValue() > noAccessThresholdMs) {
                    // 执行衰减
                    double confidence = ((Number) metadata.getOrDefault("confidence", 1.0)).doubleValue();
                    int decayCount = ((Number) metadata.getOrDefault("decayCount", 0)).intValue();

                    confidence = Math.max(0.0, confidence - decayFactor);
                    decayCount++;

                    if (confidence < minConfidence) {
                        // 删除
                        milvusClient.delete(DeleteParam.newBuilder()
                                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                                .withExpr("id == \"" + id + "\"")
                                .build());
                        deletedCount++;
                        logger.debug("删除低置信度记忆: id={}, confidence={}", id, confidence);
                    } else {
                        // 更新
                        metadata.put("confidence", confidence);
                        metadata.put("decayCount", decayCount);

                        List<io.milvus.param.dml.InsertParam.Field> fields = new ArrayList<>();
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "id", Collections.singletonList(id)));
                        fields.add(new io.milvus.param.dml.InsertParam.Field(
                                "metadata", Collections.singletonList(gson.toJson(metadata))));

                        milvusClient.upsert(UpsertParam.newBuilder()
                                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                                .withFields(fields)
                                .build());
                        decayedCount++;
                    }
                }
            }

            logger.info("记忆衰减完成: 衰减 {} 条, 删除 {} 条", decayedCount, deletedCount);

        } catch (Exception e) {
            logger.error("记忆衰减周期执行失败", e);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemoryDecayService.java
git commit -m "feat: add MemoryDecayService for scheduled confidence decay"
```

---

## Phase 2: 记忆 CRUD + Agent 工具

### Task 2.1: MemoryManager 记忆 CRUD 服务

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemoryManager.java`

**Interfaces:**
- Consumes: `MilvusServiceClient`、`VectorEmbeddingService`、`MemoryProperties`、`MilvusConstants`
- Produces: `insertMemory()`、`searchSimilarMemories()`、`getAllMemories(userId)`、`deleteMemory(id)`、`deleteAllMemories(userId)`、`updateMemory()` 方法

- [ ] **Step 1: 创建 MemoryManager**

```java
package org.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.example.config.MemoryProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private MemoryProperties memoryProperties;

    private final Gson gson = new Gson();

    /**
     * 插入一条新的记忆
     */
    public String insertMemory(String userId, String content, String type,
                                double confidence, String sourceSession) {
        String id = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        int ttlSeconds = getTtlSeconds(type);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", type);
        metadata.put("confidence", confidence);
        metadata.put("sourceSession", sourceSession);
        metadata.put("createdAt", now);
        metadata.put("updatedAt", now);
        metadata.put("lastAccessedAt", now);
        metadata.put("ttlSeconds", ttlSeconds);
        metadata.put("decayCount", 0);

        // 向量化
        List<Float> vector = embeddingService.generateEmbedding(content);

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("user_id", Collections.singletonList(userId)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(gson.toJson(metadata))));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<io.milvus.param.dml.InsertParam.InsertResp> response = milvusClient.insert(insertParam);
        if (response.getStatus() != 0) {
            logger.error("插入记忆失败: {}", response.getMessage());
            return null;
        }

        logger.info("记忆已插入: id={}, type={}, content（截断）={}", id, type,
                content.length() > 50 ? content.substring(0, 50) + "..." : content);
        return id;
    }

    /**
     * 向量搜索相似记忆（同一 userId）
     */
    public List<MemoryResult> searchSimilarMemories(String userId, String query, int topK) {
        try {
            List<Float> queryVector = embeddingService.generateQueryVector(query);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withMetricType(MetricType.L2)
                    .withTopK(topK)
                    .withExpr("user_id == \"" + userId + "\"")
                    .withOutFields(Arrays.asList("id", "content", "metadata"))
                    .withParams("{\"nprobe\": 10}")
                    .build();

            R<SearchResultsWrapper> response = milvusClient.search(searchParam);
            if (response.getStatus() != 0) {
                logger.warn("搜索记忆失败: {}", response.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = response.getData();
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<MemoryResult> results = new ArrayList<>();

            for (int i = 0; i < scores.size(); i++) {
                SearchResultsWrapper.IDScore score = scores.get(i);
                Map<String, Object> fieldMap = wrapper.getFieldValues().get(i);

                MemoryResult result = new MemoryResult();
                result.setId((String) fieldMap.get("id"));
                result.setContent((String) fieldMap.get("content"));

                Object metaObj = fieldMap.get("metadata");
                if (metaObj instanceof String) {
                    Map<String, Object> meta = gson.fromJson((String) metaObj,
                            new TypeToken<Map<String, Object>>(){}.getType());
                    result.setType((String) meta.getOrDefault("type", "UNKNOWN"));
                    result.setConfidence(((Number) meta.getOrDefault("confidence", 0.0)).doubleValue());
                }

                result.setScore(1.0f - score.getScore());  // L2 距离 → 相似度
                results.add(result);
            }

            // 更新 lastAccessedAt
            for (MemoryResult r : results) {
                touchMemory(r.getId());
            }

            return results;
        } catch (Exception e) {
            logger.error("搜索记忆异常", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户的所有记忆（按类型分组，供前端面板使用）
     */
    public Map<String, List<MemoryResult>> getAllMemories(String userId) {
        try {
            milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .build()
            );

            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withExpr("user_id == \"" + userId + "\"")
                    .withOutFields(Arrays.asList("id", "content", "metadata"))
                    .withLimit(1000L)
                    .build();

            R<QueryResultsWrapper> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0) {
                return Collections.emptyMap();
            }

            QueryResultsWrapper wrapper = response.getData();
            Map<String, List<MemoryResult>> grouped = new LinkedHashMap<>();
            grouped.put("facts", new ArrayList<>());
            grouped.put("profiles", new ArrayList<>());
            grouped.put("preferences", new ArrayList<>());

            for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                MemoryResult result = new MemoryResult();
                result.setId((String) record.get("id"));
                result.setContent((String) record.get("content"));

                Object metaObj = record.get("metadata");
                if (metaObj == null) continue;

                Map<String, Object> meta;
                if (metaObj instanceof String) {
                    meta = gson.fromJson((String) metaObj, new TypeToken<Map<String, Object>>(){}.getType());
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) metaObj;
                    meta = m;
                }

                result.setType((String) meta.getOrDefault("type", "UNKNOWN"));
                result.setConfidence(((Number) meta.getOrDefault("confidence", 0.0)).doubleValue());
                result.setSourceSession((String) meta.getOrDefault("sourceSession", ""));
                result.setCreatedAt(((Number) meta.getOrDefault("createdAt", 0L)).longValue());
                result.setLastAccessedAt(((Number) meta.getOrDefault("lastAccessedAt", 0L)).longValue());
                result.setDecayCount(((Number) meta.getOrDefault("decayCount", 0)).intValue());

                switch (result.getType()) {
                    case "FACT":
                        grouped.get("facts").add(result); break;
                    case "PROFILE":
                        grouped.get("profiles").add(result); break;
                    case "PREFERENCE":
                        grouped.get("preferences").add(result); break;
                    default:
                        grouped.get("facts").add(result);
                }
            }

            return grouped;
        } catch (Exception e) {
            logger.error("获取所有记忆失败", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 查询用户特定类型的记忆（供 System Prompt 注入使用）
     */
    public List<MemoryResult> getMemoriesByTypes(String userId, List<String> types, int maxLength) {
        Map<String, List<MemoryResult>> all = getAllMemories(userId);
        List<MemoryResult> result = new ArrayList<>();

        for (String type : types) {
            List<MemoryResult> typeMemories;
            switch (type) {
                case "PROFILE":
                    typeMemories = all.get("profiles"); break;
                case "PREFERENCE":
                    typeMemories = all.get("preferences"); break;
                default:
                    typeMemories = all.get("facts"); break;
            }
            if (typeMemories != null) {
                result.addAll(typeMemories);
            }
        }

        // 按置信度降序
        result.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));

        // 截断到 maxLength
        int total = 0;
        List<MemoryResult> truncated = new ArrayList<>();
        for (MemoryResult r : result) {
            if (total + r.getContent().length() > maxLength) break;
            truncated.add(r);
            total += r.getContent().length();
        }
        return truncated;
    }

    /**
     * 删除单条记忆
     */
    public boolean deleteMemory(String memoryId) {
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withExpr("id == \"" + memoryId + "\"")
                .build();

        R<io.milvus.param.dml.DeleteParam.DeleteResp> response = milvusClient.delete(deleteParam);
        if (response.getStatus() != 0) {
            logger.warn("删除记忆失败: {}", response.getMessage());
            return false;
        }
        logger.info("记忆已删除: id={}", memoryId);
        return true;
    }

    /**
     * 清空用户所有记忆
     */
    public long deleteAllMemories(String userId) {
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withExpr("user_id == \"" + userId + "\"")
                .build();

        R<io.milvus.param.dml.DeleteParam.DeleteResp> response = milvusClient.delete(deleteParam);
        if (response.getStatus() != 0) {
            logger.warn("清空记忆失败: {}", response.getMessage());
            return 0;
        }
        long deleted = response.getData().getDeleteCnt();
        logger.info("已清空用户 {} 的全部记忆，删除 {} 条", userId, deleted);
        return deleted;
    }

    /**
     * 更新记忆内容和元数据（用于冲突处理 UPDATE/MERGE）
     */
    public boolean updateMemory(String memoryId, String newContent,
                                 Map<String, Object> newMetadata) {
        newMetadata.put("updatedAt", Instant.now().toEpochMilli());

        List<Float> vector = embeddingService.generateEmbedding(newContent);

        List<io.milvus.param.dml.InsertParam.Field> fields = new ArrayList<>();
        fields.add(new io.milvus.param.dml.InsertParam.Field(
                "id", Collections.singletonList(memoryId)));
        fields.add(new io.milvus.param.dml.InsertParam.Field(
                "vector", Collections.singletonList(vector)));
        fields.add(new io.milvus.param.dml.InsertParam.Field(
                "content", Collections.singletonList(newContent)));
        fields.add(new io.milvus.param.dml.InsertParam.Field(
                "metadata", Collections.singletonList(gson.toJson(newMetadata))));

        UpsertParam upsertParam = UpsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<io.milvus.param.dml.UpsertParam.UpsertResp> response = milvusClient.upsert(upsertParam);
        if (response.getStatus() != 0) {
            logger.warn("更新记忆失败: {}", response.getMessage());
            return false;
        }
        logger.info("记忆已更新: id={}", memoryId);
        return true;
    }

    /**
     * 更新记忆的 lastAccessedAt（touch）
     */
    private void touchMemory(String memoryId) {
        try {
            long now = Instant.now().toEpochMilli();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("lastAccessedAt", now);

            List<io.milvus.param.dml.InsertParam.Field> fields = new ArrayList<>();
            fields.add(new io.milvus.param.dml.InsertParam.Field(
                    "id", Collections.singletonList(memoryId)));
            fields.add(new io.milvus.param.dml.InsertParam.Field(
                    "metadata", Collections.singletonList(gson.toJson(meta))));

            milvusClient.upsert(UpsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MEMORY_COLLECTION_NAME)
                    .withFields(fields)
                    .build());
        } catch (Exception ignored) {
            // touch 失败不影响主流程
        }
    }

    private int getTtlSeconds(String type) {
        return switch (type) {
            case "FACT" -> memoryProperties.getTtl().getFactHours() * 3600;
            case "PROFILE" -> memoryProperties.getTtl().getProfileHours() * 3600;
            case "PREFERENCE" -> memoryProperties.getTtl().getPreferenceHours() * 3600;
            default -> 0;
        };
    }

    /**
     * 记忆搜索结果 DTO
     */
    public static class MemoryResult {
        private String id;
        private String type;
        private String content;
        private double confidence;
        private double score;
        private String sourceSession;
        private long createdAt;
        private long lastAccessedAt;
        private int decayCount;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getSourceSession() { return sourceSession; }
        public void setSourceSession(String sourceSession) { this.sourceSession = sourceSession; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getLastAccessedAt() { return lastAccessedAt; }
        public void setLastAccessedAt(long lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
        public int getDecayCount() { return decayCount; }
        public void setDecayCount(int decayCount) { this.decayCount = decayCount; }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemoryManager.java
git commit -m "feat: add MemoryManager for memory CRUD with Milvus"
```

---

### Task 2.2: MemorySearchService 纯向量搜索

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemorySearchService.java`

**Interfaces:**
- Consumes: `MemoryManager.searchSimilarMemories()`
- Produces: `search(String userId, String query, int topK)` → `List<MemoryManager.MemoryResult>`，格式化 JSON 返回值

- [ ] **Step 1: 创建 MemorySearchService**

```java
package org.example.service;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemorySearchService {

    private static final Logger logger = LoggerFactory.getLogger(MemorySearchService.class);

    @Autowired
    private MemoryManager memoryManager;

    private final Gson gson = new Gson();

    /**
     * 搜索用户记忆（供 RecallMemoryTool 调用）
     * @return JSON 格式的搜索结果
     */
    public String search(String userId, String query, int topK) {
        if (userId == null || userId.isEmpty()) {
            return "{\"error\": \"userId is required\", \"results\": []}";
        }

        logger.info("搜索记忆: userId={}, query={}, topK={}", userId, query, topK);

        List<MemoryManager.MemoryResult> results =
                memoryManager.searchSimilarMemories(userId, query, topK);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("totalResults", results.size());

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (MemoryManager.MemoryResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("type", r.getType());
            item.put("content", r.getContent());
            item.put("confidence", r.getConfidence());
            item.put("score", Math.round(r.getScore() * 100.0) / 100.0);
            resultList.add(item);
        }
        response.put("results", resultList);

        return gson.toJson(response);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemorySearchService.java
git commit -m "feat: add MemorySearchService for vector-based memory search"
```

---

### Task 2.3: RecallMemoryTool Agent 工具

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/agent/tool/RecallMemoryTool.java`

**Interfaces:**
- Consumes: `MemorySearchService.search()`
- Produces: `recallMemory(query, topK)` — Spring AI `@Tool` 方法

- [ ] **Step 1: 创建 RecallMemoryTool**

```java
package org.example.agent.tool;

import org.example.service.MemorySearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.agent.tool.Tool;
import com.alibaba.cloud.ai.graph.agent.tool.ToolParam;

@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class RecallMemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(RecallMemoryTool.class);

    @Autowired
    private MemorySearchService memorySearchService;

    /**
     * 由于 Spring AI Agent Framework 的 @Tool 注解在方法上，
     * 但 userId 需要从会话上下文获取。这里需要配合 ChatController
     * 将 userId 通过 ThreadLocal 或方法参数传递。
     *
     * 当前方案：在 ChatController 中设置
     * RecallMemoryTool.setCurrentUserId(userId)，
     * Agent 调用时从 ThreadLocal 读取。
     */
    private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    public static void setCurrentUserId(String userId) {
        currentUserId.set(userId);
    }

    public static void clearCurrentUserId() {
        currentUserId.remove();
    }

    @Tool(description = """
        查询用户的历史记忆。当需要回忆用户之前提到过的技术细节、
        历史决策、具体偏好时调用此工具。返回匹配的记忆内容和置信度。""")
    public String recallMemory(
            @ToolParam(description = "搜索查询文本，用自然语言描述要查找的记忆内容") String query,
            @ToolParam(description = "返回数量，默认3，最大10") Integer topK) {

        String userId = currentUserId.get();
        if (userId == null || userId.isEmpty()) {
            return "{\"error\": \"未设置用户ID，无法查询记忆\", \"results\": []}";
        }

        int k = topK != null ? Math.min(topK, 10) : 3;
        logger.info("Agent 调用 recallMemory: userId={}, query={}, topK={}", userId, query, k);

        return memorySearchService.search(userId, query, k);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/agent/tool/RecallMemoryTool.java
git commit -m "feat: add RecallMemoryTool for agent-driven memory search"
```

---

### Task 2.4: ForgetMemoryTool Agent 工具

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/agent/tool/ForgetMemoryTool.java`

**Interfaces:**
- Consumes: `MemoryManager.deleteMemory()`、`MemoryManager.searchSimilarMemories()`
- Produces: `forgetMemory(target, userId)` — Spring AI `@Tool` 方法

- [ ] **Step 1: 创建 ForgetMemoryTool**

```java
package org.example.agent.tool;

import org.example.service.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.agent.tool.Tool;
import com.alibaba.cloud.ai.graph.agent.tool.ToolParam;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class ForgetMemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(ForgetMemoryTool.class);

    @Autowired
    private MemoryManager memoryManager;

    @Tool(description = """
        删除用户的记忆。当用户明确要求"忘记"某些信息时调用。
        先按关键词搜索记忆，确认匹配后删除。返回删除结果。""")
    public String forgetMemory(
            @ToolParam(description = "要删除的记忆关键词，用于搜索匹配的记忆") String target,
            @ToolParam(description = "当前用户ID") String userId) {

        logger.info("Agent 调用 forgetMemory: userId={}, target={}", userId, target);

        if (userId == null || userId.isEmpty()) {
            return "{\"success\": false, \"message\": \"未设置用户ID\"}";
        }

        // 1. 先搜索匹配的记忆
        List<MemoryManager.MemoryResult> matches =
                memoryManager.searchSimilarMemories(userId, target, 3);

        if (matches.isEmpty()) {
            return "{\"success\": false, \"message\": \"未找到匹配的记忆\", \"deletedCount\": 0}";
        }

        // 2. 删除匹配的记忆
        int deleted = 0;
        for (MemoryManager.MemoryResult match : matches) {
            if (match.getScore() > 0.5) {  // 相似度阈值
                if (memoryManager.deleteMemory(match.getId())) {
                    deleted++;
                }
            }
        }

        return String.format(
            "{\"success\": true, \"message\": \"已删除 %d 条记忆\", \"deletedCount\": %d}",
            deleted, deleted);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/agent/tool/ForgetMemoryTool.java
git commit -m "feat: add ForgetMemoryTool for agent-driven memory deletion"
```

---

## Phase 3: 记忆提取 + 注入打通

### Task 3.1: MemoryExtractor 异步提取服务

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemoryExtractor.java`

**Interfaces:**
- Consumes: MemoryProperties、MemoryManager、SessionManager.getFullHistory()、DashScopeLlmClient
- Produces: `extractAsync(sessionId, userId)` — `@Async("memoryExecutor")` 方法

- [ ] **Step 1: 检查 DashScopeLlmClient 是否可复用**

确认 `D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17\src\main\java\org\example\service\DashScopeLlmClient.java` 存在且有 `chat(String model, String systemPrompt, String userMessage)` 方法。若方法签名不同，MemoryExtractor 中可直接使用 `DashScopeApi` 构建临时客户端。

- [ ] **Step 2: 创建 MemoryExtractor**

```java
package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.example.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class MemoryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractor.class);

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MemoryProperties memoryProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    private final Gson gson = new Gson();

    private static final String MEMORY_LOCK_SUFFIX = ":memory-lock";

    /**
     * 异步触发记忆提取
     * 由 SessionManager.addMessage() 检测增量达标后调用
     */
    @Async("memoryExecutor")
    public void extractAsync(String sessionId, String userId) {
        if (userId == null || userId.isEmpty()) {
            logger.debug("无 userId，跳过记忆提取 - sessionId={}", sessionId);
            return;
        }

        // 分布式锁防重
        String lockKey = "session:" + sessionId + MEMORY_LOCK_SUFFIX;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(locked)) {
            logger.debug("记忆提取锁已被持有，跳过 - sessionId={}", sessionId);
            return;
        }

        try {
            logger.info("开始异步记忆提取 - sessionId={}, userId={}", sessionId, userId);
            doExtract(sessionId, userId);
        } catch (Exception e) {
            logger.error("记忆提取失败 - sessionId={}", sessionId, e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void doExtract(String sessionId, String userId) {
        // 1. 读取对话历史
        List<Map<String, String>> history = sessionManager.getFullHistory(sessionId);
        if (history.isEmpty()) {
            logger.debug("无对话历史，跳过提取");
            return;
        }

        // 限制最大分析条数
        int maxBatch = memoryProperties.getExtraction().getMaxBatchMessages();
        if (history.size() > maxBatch) {
            history = history.subList(history.size() - maxBatch, history.size());
        }

        // 2. 读取已有记忆
        List<MemoryManager.MemoryResult> existingMemories =
                memoryManager.getMemoriesByTypes(userId,
                        Arrays.asList("FACT", "PROFILE", "PREFERENCE"), 2000);
        String existingSummary = existingMemories.stream()
                .map(m -> String.format("[%s] %s (置信度:%.0f%%)",
                        m.getType(), m.getContent(), m.getConfidence() * 100))
                .collect(Collectors.joining("\n"));

        // 3. 构建提取 prompt
        String conversationText = history.stream()
                .map(m -> ("user".equals(m.get("role")) ? "用户: " : "助手: ") + m.get("content"))
                .collect(Collectors.joining("\n"));

        String extractionPrompt = buildExtractionPrompt(existingSummary, conversationText);

        // 4. 调用轻量 LLM 提取
        String model = memoryProperties.getExtraction().getModel();
        String llmResponse = callLlm(model, "你是一个记忆提取器。", extractionPrompt);

        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            logger.debug("LLM 返回空，无法提取记忆");
            return;
        }

        // 5. 解析提取结果
        String jsonBlock = extractJson(llmResponse);
        if (jsonBlock == null) {
            logger.debug("未能从 LLM 响应中解析 JSON");
            return;
        }

        Map<String, Object> result;
        try {
            result = gson.fromJson(jsonBlock, new TypeToken<Map<String, Object>>(){}.getType());
        } catch (Exception e) {
            logger.warn("JSON 解析失败: {}", e.getMessage());
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> memories = (List<Map<String, Object>>) result.get("memories");
        if (memories == null || memories.isEmpty()) {
            logger.info("本轮未提取到新记忆");
            return;
        }

        // 6. 逐条处理：冲突检测 → 写入
        int newCount = 0, updateCount = 0, mergeCount = 0;
        for (Map<String, Object> memory : memories) {
            String type = (String) memory.get("type");
            String content = (String) memory.get("content");
            double confidence = ((Number) memory.getOrDefault("confidence", 0.5)).doubleValue();

            if (type == null || content == null || content.trim().isEmpty()) continue;
            if (confidence < 0.5) continue;  // 过滤低置信度

            // 搜索冲突
            List<MemoryManager.MemoryResult> conflicts =
                    memoryManager.searchSimilarMemories(userId, content, 1);

            if (conflicts.isEmpty() || conflicts.get(0).getScore() < memoryProperties.getSearch().getScoreThreshold()) {
                // NEW
                memoryManager.insertMemory(userId, content, type, confidence, sessionId);
                newCount++;
            } else {
                // 有冲突 → LLM 判断
                MemoryManager.MemoryResult conflict = conflicts.get(0);
                String action = resolveConflict(conflict.getContent(), conflict.getConfidence(),
                        content, confidence);

                switch (action) {
                    case "UPDATE":
                        Map<String, Object> newMeta = new LinkedHashMap<>();
                        newMeta.put("type", type);
                        newMeta.put("confidence", Math.max(confidence, conflict.getConfidence()));
                        newMeta.put("sourceSession", sessionId);
                        newMeta.put("decayCount", 0);
                        memoryManager.updateMemory(conflict.getId(), content, newMeta);
                        updateCount++;
                        break;
                    case "MERGE":
                        String mergedContent = resolveMerge(conflict.getContent(), content);
                        Map<String, Object> mergeMeta = new LinkedHashMap<>();
                        mergeMeta.put("type", type);
                        mergeMeta.put("confidence", Math.max(confidence, conflict.getConfidence()));
                        mergeMeta.put("sourceSession", sessionId);
                        mergeMeta.put("decayCount", 0);
                        memoryManager.updateMemory(conflict.getId(), mergedContent, mergeMeta);
                        mergeCount++;
                        break;
                    default: // NEW
                        memoryManager.insertMemory(userId, content, type, confidence, sessionId);
                        newCount++;
                }
            }
        }

        // 7. 更新 session meta
        SessionManager.SessionMeta meta = sessionManager.getSessionMeta(sessionId);
        if (meta != null) {
            meta.setLastExtractedMessageCount(meta.getMessagePairCount());
            sessionManager.updateSessionMeta(sessionId, meta);
        }

        logger.info("记忆提取完成: 新增{}条, 更新{}条, 合并{}条", newCount, updateCount, mergeCount);
    }

    private String buildExtractionPrompt(String existingMemories, String conversation) {
        return String.format("""
            分析以下对话，提取关于用户的重要信息。

            已有记忆：
            %s

            对话历史：
            %s

            请提取三类信息：
            1. FACT（事实结论）：用户明确提到的技术事实、环境信息、历史决策结果
            2. PROFILE（用户画像）：用户的职业角色、技能领域、职责范围
            3. PREFERENCE（行为偏好）：用户表达的信息呈现偏好、工作习惯、交流风格

            要求：
            - 只提取明确的信息，不要推测
            - 每条记忆置信度 0-1，模糊信息给低分
            - 如果对话中没有值得提取的信息，返回空列表
            - 输出 JSON: {"memories": [{"type": "FACT", "content": "...", "confidence": 0.9}]}
            """, existingMemories.isEmpty() ? "（无已有记忆）" : existingMemories, conversation);
    }

    private String resolveConflict(String oldContent, double oldConf,
                                    String newContent, double newConf) {
        String prompt = String.format("""
            用户已有以下记忆：
            旧记忆: "%s" (置信度: %.0f%%)

            从最新对话中提取到：
            新记忆: "%s" (置信度: %.0f%%)

            判断新旧记忆的关系：
            - UPDATE: 新信息是旧信息的更新（如版本升级），覆盖旧记忆
            - MERGE: 两者可以合并为一条更完整的记忆
            - NEW: 两者是不同的信息，应该各自保留

            输出 JSON: {"action": "UPDATE|MERGE|NEW", "reason": "..."}
            """, oldContent, oldConf * 100, newContent, newConf * 100);

        String response = callLlm("qwen-turbo", "你是一个记忆冲突判断器。", prompt);
        if (response == null) return "NEW"; // 超时默认 NEW

        String json = extractJson(response);
        if (json == null) return "NEW";

        try {
            Map<String, Object> result = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            return (String) result.getOrDefault("action", "NEW");
        } catch (Exception e) {
            return "NEW";
        }
    }

    private String resolveMerge(String oldContent, String newContent) {
        String prompt = String.format("""
            以下两条记忆描述的是同一信息，请将它们合并为一条简洁完整的记忆：

            记忆1: "%s"
            记忆2: "%s"

            输出合并后的记忆文本（仅输出文本，不要 JSON）。
            """, oldContent, newContent);

        String response = callLlm("qwen-turbo", "你是一个信息整合器。", prompt);
        return response != null ? response.trim() : (oldContent + "；" + newContent);
    }

    private String callLlm(String model, String systemPrompt, String userMessage) {
        try {
            DashScopeApi api = DashScopeApi.builder().apiKey(dashScopeApiKey).build();
            // 使用 DashScopeApi 的 chat 方法
            // 注：实际调用方式取决于 DashScopeApi 的具体 API
            // 如果 DashScopeApi 不支持直接 chat，回退到 HttpURLConnection
            return callLlmViaHttp(model, systemPrompt, userMessage);
        } catch (Exception e) {
            logger.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 通过 HTTP 直接调用 DashScope LLM API
     * 参考现有 DashScopeLlmClient 的实现模式
     */
    private String callLlmViaHttp(String model, String systemPrompt, String userMessage) {
        try {
            java.net.URL url = new java.net.URL("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + dashScopeApiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> sysMsg = new LinkedHashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            body.put("messages", messages);
            body.put("temperature", 0.3);
            body.put("max_tokens", 2000);

            java.io.OutputStream os = conn.getOutputStream();
            os.write(gson.toJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                logger.warn("LLM HTTP 返回非 200: {}", code);
                return null;
            }

            java.util.Scanner scanner = new java.util.Scanner(
                    conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8).useDelimiter("\\A");
            String response = scanner.hasNext() ? scanner.next() : "";
            scanner.close();

            Map<String, Object> respMap = gson.fromJson(response,
                    new TypeToken<Map<String, Object>>(){}.getType());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                return (String) message.get("content");
            }
            return null;
        } catch (Exception e) {
            logger.warn("LLM HTTP 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }
}
```

- [ ] **Step 2: 确认 SessionManager 需要新增的方法**

`MemoryExtractor` 调用了 `sessionManager.updateSessionMeta()` —— 需要先确认该方法是否存在。当前 `SessionManager` 的 `updateMeta()` 是 private。需要在 Task 3.2 中将其升级为包内可见。

- [ ] **Step 3: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/MemoryExtractor.java
git commit -m "feat: add MemoryExtractor for async batch memory extraction"
```

---

### Task 3.2: SessionManager 改造

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/SessionManager.java`

**Interfaces:**
- Consumes: `MemoryExtractor.extractAsync()`、`MemoryProperties`
- Produces: `SessionMeta.lastExtractedMessageCount` 字段、`addMessage()` 触发提取逻辑

- [ ] **Step 1: SessionMeta 新增 lastExtractedMessageCount 字段**

在 `SessionMeta` 类中添加：

```java
private int lastExtractedMessageCount;

public int getLastExtractedMessageCount() { return lastExtractedMessageCount; }
public void setLastExtractedMessageCount(int lastExtractedMessageCount) {
    this.lastExtractedMessageCount = lastExtractedMessageCount;
}
```

- [ ] **Step 2: 新增 updateSessionMeta() 包内可见方法**

在现有 `private void updateMeta()` 方法后面新增：

```java
/**
 * 更新会话元数据（供 MemoryExtractor 使用）
 */
void updateSessionMeta(String sessionId, SessionMeta meta) {
    try {
        String metaJson = redisObjectMapper.writeValueAsString(meta);
        writeWithTTL(metaKey(sessionId), metaJson);
    } catch (JsonProcessingException e) {
        logger.warn("序列化元数据失败 - SessionId: {}", sessionId, e);
    }
}
```

- [ ] **Step 3: 注入 MemoryExtractor 和 MemoryProperties**

```java
@Autowired(required = false)
private MemoryExtractor memoryExtractor;

@Autowired(required = false)
private MemoryProperties memoryProperties;
```

- [ ] **Step 4: addMessage() 末尾新增记忆提取触发逻辑**

在 `addMessage()` 方法的末尾（步骤 7 摘要触发之后）添加：

```java
// 8. 检查是否需要触发记忆提取
if (memoryProperties != null && memoryProperties.isEnabled()
        && memoryExtractor != null && userId != null) {
    SessionMeta meta = getSessionMeta(sessionId);
    int newPairs = messagePairCount - (meta != null ? meta.getLastExtractedMessageCount() : 0);
    if (newPairs >= memoryProperties.getExtraction().getTriggerMessageCount()) {
        logger.info("触发异步记忆提取 - sessionId={}, 新增{}对消息", sessionId, newPairs);
        memoryExtractor.extractAsync(sessionId, userId);
    }
}
```

- [ ] **Step 5: addMessage() 方法签名变更 —— 新增 userId 参数**

将 `addMessage(String sessionId, String userMessage, String aiMessage)` 改为：

```java
public void addMessage(String sessionId, String userMessage, String aiMessage, String userId)
```

现有调用者（ChatController）需同步修改（在 Task 3.3 中处理）。

- [ ] **Step 6: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

注意：此时编译可能会因为 ChatController 调用 `addMessage()` 的签名不匹配而失败，需要 Task 3.3 一起完成后再编译通过。

- [ ] **Step 7: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/SessionManager.java
git commit -m "feat: add memory extraction trigger to SessionManager.addMessage()"
```

---

### Task 3.3: ChatController 改造

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/controller/ChatController.java`

**Interfaces:**
- Consumes: MemoryManager、SessionManager（新签名）、RecallMemoryTool.setCurrentUserId()
- Produces: ChatRequest.userId 字段、chat()/chatStream() 传递 userId

- [ ] **Step 1: ChatRequest 新增 userId 字段**

```java
@Setter
@Getter
public static class ChatRequest {
    private String id;        // sessionId
    private String question;
    private String userId;    // 新增：用户标识
}
```

- [ ] **Step 2: chat() 方法中使用 userId**

在 `chat()` 方法中，`sessionManager.addMessage()` 调用处改为传入 userId：

```java
// 原: sessionManager.addMessage(sessionId, request.getQuestion(), fullAnswer);
sessionManager.addMessage(sessionId, request.getQuestion(), fullAnswer, request.getUserId());
```

同时在方法开头设置 RecallMemoryTool 的 ThreadLocal：

```java
RecallMemoryTool.setCurrentUserId(request.getUserId());
try {
    // ... 现有对话逻辑 ...
} finally {
    RecallMemoryTool.clearCurrentUserId();
}
```

- [ ] **Step 3: chatStream() 方法中同样处理**

在 SSE done 事件之前，设置 userId 并更新 addMessage 调用签名。

- [ ] **Step 4: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/controller/ChatController.java
git commit -m "feat: add userId to ChatRequest and wire into session/memory flow"
```

---

### Task 3.4: ChatService System Prompt 增强

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/ChatService.java`

**Interfaces:**
- Consumes: MemoryManager、MemoryProperties、RecallMemoryTool、ForgetMemoryTool
- Produces: `buildSystemPrompt()` 注入用户画像/偏好区块；`buildMethodToolsArray()` 追加记忆工具

- [ ] **Step 1: 注入 MemoryManager / MemoryProperties**

```java
@Autowired(required = false)
private MemoryManager memoryManager;

@Autowired(required = false)
private MemoryProperties memoryProperties;

@Value("${memory.enabled:false}")
private boolean memoryEnabled;

// ===== 记忆工具 =====
@Autowired(required = false)
private RecallMemoryTool recallMemoryTool;

@Autowired(required = false)
private ForgetMemoryTool forgetMemoryTool;
```

- [ ] **Step 2: buildSystemPrompt() 新增画像注入逻辑**

在 `buildSystemPrompt()` 方法中，return 之前、Agentic RAG 指令之后添加：

```java
// 记忆注入：用户画像 + 偏好
if (memoryEnabled && memoryManager != null && memoryProperties != null) {
    String memoryBlock = buildMemoryProfileBlock(userId);  // 需要传入 userId
    if (!memoryBlock.isEmpty()) {
        systemPromptBuilder.append(memoryBlock);
    }
}
```

新增 `buildMemoryProfileBlock(String userId)` 私有方法：

```java
private String buildMemoryProfileBlock(String userId) {
    if (userId == null || userId.isEmpty()) return "";

    List<String> types = new ArrayList<>();
    if (memoryProperties.getSystemPrompt().isInjectProfile()) types.add("PROFILE");
    if (memoryProperties.getSystemPrompt().isInjectPreferences()) types.add("PREFERENCE");
    if (types.isEmpty()) return "";

    List<MemoryManager.MemoryResult> memories = memoryManager.getMemoriesByTypes(
            userId, types,
            memoryProperties.getSystemPrompt().getMaxLength()
    );

    if (memories.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("\n## 用户画像\n\n");
    sb.append("关于用户你知道：\n");
    for (MemoryManager.MemoryResult m : memories) {
        sb.append("- ").append(m.getContent()).append("\n");
    }
    sb.append("\n");
    return sb.toString();
}
```

- [ ] **Step 3: buildSystemPrompt() 新增 userId 参数**

将方法签名中的 `(List<Map<String, String>> history, String summary)` 改为：

```java
public String buildSystemPrompt(List<Map<String, String>> history, String summary, String userId)
```

并同步修改不带 summary 的重载方法。

- [ ] **Step 4: buildMethodToolsArray() 追加记忆工具**

```java
// 记忆工具（仅在 memory.enabled 时注册）
if (memoryEnabled) {
    if (recallMemoryTool != null) toolList.add(recallMemoryTool);
    if (forgetMemoryTool != null) toolList.add(forgetMemoryTool);
}
```

- [ ] **Step 5: 编译验证 + 修复所有调用点**

ChatController 中调用 `buildSystemPrompt()` 的地方需要传入 userId。编译修复。

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 6: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/ChatService.java
git commit -m "feat: inject user profile/preferences into system prompt and register memory tools"
```

---

## Phase 4: REST API + 前端面板

### Task 4.1: MemoryController REST API

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/controller/MemoryController.java`

**Interfaces:**
- Consumes: MemoryManager
- Produces: GET /api/memory/panel, DELETE /api/memory/{id}, DELETE /api/memory/clear

- [ ] **Step 1: 创建 MemoryController**

```java
package org.example.controller;

import org.example.service.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
    public ResponseEntity<Map<String, Object>> getMemoryPanel(
            @RequestParam("userId") String userId) {

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

            // 转换为前端友好的格式
            response.put("facts", formatForFrontend(grouped.getOrDefault("facts", Collections.emptyList())));
            response.put("profiles", formatForFrontend(grouped.getOrDefault("profiles", Collections.emptyList())));
            response.put("preferences", formatForFrontend(grouped.getOrDefault("preferences", Collections.emptyList())));

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
            @PathVariable("memoryId") String memoryId,
            @RequestParam("userId") String userId) {

        logger.info("删除记忆 - userId={}, memoryId={}", userId, memoryId);

        boolean success = memoryManager.deleteMemory(memoryId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("message", success ? "记忆已删除" : "删除失败");

        return ResponseEntity.ok(response);
    }

    /**
     * 清空用户所有记忆
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearMemories(
            @RequestParam("userId") String userId) {

        logger.info("清空记忆 - userId={}", userId);

        long deleted = memoryManager.deleteAllMemories(userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "已清空全部记忆");
        response.put("deletedCount", deleted);

        return ResponseEntity.ok(response);
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
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/controller/MemoryController.java
git commit -m "feat: add MemoryController REST API for memory panel and deletion"
```

---

### Task 4.2: 前端「我的记忆」面板

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/static/index.html`
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/static/app.js`
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/static/styles.css`

**Interfaces:**
- Consumes: GET /api/memory/panel?userId=xxx, DELETE /api/memory/{id}?userId=xxx, DELETE /api/memory/clear?userId=xxx
- Produces: 侧边栏入口 + 面板渲染 + Tab 切换 + 删除交互

- [ ] **Step 1: index.html — 侧边栏新增「我的记忆」入口**

在侧边栏的对话列表之后、文件上传按钮之前，添加：

```html
<div class="sidebar-item" id="memory-panel-btn" onclick="app.toggleMemoryPanel()">
    <span>🧠</span> 我的记忆
</div>
```

以及记忆面板容器（默认隐藏）：

```html
<!-- 记忆面板 -->
<div id="memory-panel" class="memory-panel" style="display: none;">
    <div class="memory-panel-header">
        <h3>🧠 我的记忆</h3>
        <p>系统根据和你的对话自动提取，你可以随时查看和删除</p>
        <button class="memory-close-btn" onclick="app.toggleMemoryPanel()">✕</button>
    </div>
    <div class="memory-stats" id="memory-stats"></div>
    <div class="memory-tabs" id="memory-tabs">
        <button class="memory-tab active" data-tab="facts">📌 事实结论</button>
        <button class="memory-tab" data-tab="profiles">👤 用户画像</button>
        <button class="memory-tab" data-tab="preferences">🎯 行为偏好</button>
    </div>
    <div class="memory-list" id="memory-list"></div>
    <div class="memory-empty" id="memory-empty" style="display: none;">
        📭 暂无记忆
    </div>
</div>
```

- [ ] **Step 2: styles.css — 记忆面板样式**

新增样式（基础功能样式，后续由前端 skill 美化）：

```css
/* 记忆面板 */
.memory-panel {
    position: fixed;
    top: 0; right: 0;
    width: 420px;
    height: 100vh;
    background: #fff;
    box-shadow: -2px 0 20px rgba(0,0,0,0.1);
    z-index: 1000;
    overflow-y: auto;
    padding: 24px;
}
.memory-panel-header {
    margin-bottom: 20px;
}
.memory-panel-header h3 { margin: 0; font-size: 18px; }
.memory-panel-header p { margin: 4px 0 0; font-size: 12px; color: #6b7280; }
.memory-close-btn {
    position: absolute; top: 16px; right: 16px;
    background: none; border: none; font-size: 20px; cursor: pointer;
}
.memory-stats { display: flex; gap: 12px; margin-bottom: 20px; }
.memory-stat-card {
    flex: 1; text-align: center; padding: 12px; border-radius: 10px;
}
.memory-stat-card .count { font-size: 24px; font-weight: 700; }
.memory-stat-card .label { font-size: 12px; color: #6b7280; }
.memory-tabs { display: flex; gap: 0; border-bottom: 2px solid #e5e7eb; margin-bottom: 16px; }
.memory-tab {
    padding: 8px 16px; font-size: 13px; border: none; background: none;
    cursor: pointer; color: #6b7280;
}
.memory-tab.active {
    color: #6366f1; border-bottom: 2px solid #6366f1; margin-bottom: -2px;
}
.memory-card {
    display: flex; gap: 12px; padding: 14px; margin-bottom: 10px;
    background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 10px;
}
.memory-card.decaying { background: #fffbeb; border-color: #fde68a; }
.memory-conf-bar {
    flex: 1; height: 6px; background: #e5e7eb; border-radius: 3px; min-width: 80px;
}
.memory-conf-fill { height: 100%; border-radius: 3px; }
.memory-delete-btn {
    padding: 4px 12px; font-size: 12px; color: #ef4444;
    background: #fef2f2; border: 1px solid #fecaca; border-radius: 6px; cursor: pointer;
}
.memory-empty {
    text-align: center; padding: 40px; color: #9ca3af;
}
```

- [ ] **Step 3: app.js — 记忆面板交互逻辑**

在 `SuperBizAgentApp` 类中添加：

```javascript
// userId 持久化
getUserId() {
    let userId = localStorage.getItem('sbiz_user_id');
    if (!userId) {
        userId = 'u_' + crypto.randomUUID();
        localStorage.setItem('sbiz_user_id', userId);
    }
    return userId;
},

// 发送请求时携带 userId
async chat(message) {
    const requestBody = {
        id: this.currentSessionId,
        question: message,
        userId: this.getUserId()
    };
    // ... 现有逻辑 ...
},

// 切换记忆面板
async toggleMemoryPanel() {
    const panel = document.getElementById('memory-panel');
    if (panel.style.display === 'none') {
        panel.style.display = 'block';
        await this.loadMemoryPanel();
    } else {
        panel.style.display = 'none';
    }
},

// 加载记忆数据
async loadMemoryPanel() {
    try {
        const userId = this.getUserId();
        const resp = await fetch(`/api/memory/panel?userId=${encodeURIComponent(userId)}`);
        const data = await resp.json();
        if (!data.success) return;

        // 渲染统计
        document.getElementById('memory-stats').innerHTML = `
            <div class="memory-stat-card" style="background:#f0f4ff">
                <div class="count" style="color:#6366f1">${data.facts.length}</div>
                <div class="label">📌 事实</div>
            </div>
            <div class="memory-stat-card" style="background:#f0fdf4">
                <div class="count" style="color:#22c55e">${data.profiles.length}</div>
                <div class="label">👤 画像</div>
            </div>
            <div class="memory-stat-card" style="background:#fff7ed">
                <div class="count" style="color:#f59e0b">${data.preferences.length}</div>
                <div class="label">🎯 偏好</div>
            </div>
        `;

        this.memoryData = data;
        this.renderMemoryTab('facts');  // 默认显示 facts tab
    } catch (e) {
        console.error('加载记忆面板失败', e);
    }
},

// Tab 切换
switchMemoryTab(tab) {
    document.querySelectorAll('.memory-tab').forEach(t => t.classList.remove('active'));
    document.querySelector(`[data-tab="${tab}"]`).classList.add('active');
    this.renderMemoryTab(tab);
},

// 渲染单个 Tab
renderMemoryTab(tab) {
    const data = this.memoryData[tab] || [];
    const list = document.getElementById('memory-list');
    const empty = document.getElementById('memory-empty');

    if (data.length === 0) {
        list.innerHTML = '';
        empty.style.display = 'block';
        return;
    }

    empty.style.display = 'none';
    list.innerHTML = data.map(m => {
        const confPercent = m.confidencePercent || Math.round(m.confidence * 100);
        const confColor = confPercent >= 70 ? '#22c55e' : confPercent >= 40 ? '#f59e0b' : '#ef4444';
        const now = Date.now();
        const hoursSinceAccess = (now - m.lastAccessedAt) / 3600000;
        const isDecaying = hoursSinceAccess > 168;  // 7 天
        const sourceDate = new Date(m.createdAt).toLocaleDateString('zh-CN');

        return `
            <div class="memory-card ${isDecaying ? 'decaying' : ''}">
                <div style="flex:1;min-width:0">
                    <div style="font-size:14px;line-height:1.5;margin-bottom:8px">${this.escapeHtml(m.content)}</div>
                    <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                        <div class="memory-conf-bar">
                            <div class="memory-conf-fill" style="width:${confPercent}%;background:${confColor}"></div>
                        </div>
                        <span style="font-size:12px;color:${confColor};font-weight:600">${confPercent}%</span>
                        <span style="font-size:12px;color:#9ca3af">${sourceDate} 会话</span>
                        ${isDecaying ? `<span style="font-size:11px;background:#fef3c7;color:#92400e;padding:2px 8px;border-radius:12px">${Math.floor(hoursSinceAccess/24)}天未访问</span>` : ''}
                        <button class="memory-delete-btn" onclick="app.deleteMemory('${m.id}')">🗑 删除</button>
                    </div>
                </div>
            </div>
        `;
    }).join('');
},

// 删除记忆
async deleteMemory(memoryId) {
    if (!confirm('确定要删除这条记忆吗？')) return;
    try {
        const userId = this.getUserId();
        const resp = await fetch(`/api/memory/${encodeURIComponent(memoryId)}?userId=${encodeURIComponent(userId)}`, { method: 'DELETE' });
        const data = await resp.json();
        if (data.success) {
            await this.loadMemoryPanel();  // 重新加载
        }
    } catch (e) {
        console.error('删除记忆失败', e);
    }
},

// HTML 转义
escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
},
```

- [ ] **Step 4: 绑定 Tab 切换事件**

在 initialize 或 init 方法中添加事件委托：

```javascript
document.getElementById('memory-tabs').addEventListener('click', (e) => {
    if (e.target.classList.contains('memory-tab')) {
        const tab = e.target.dataset.tab;
        this.switchMemoryTab(tab);
    }
});
```

- [ ] **Step 5: 编译验证（前端无需编译，但确保后端兼容）**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

- [ ] **Step 6: Commit**

```bash
git add SuperBizAgent-release-2026-05-17/src/main/resources/static/
git commit -m "feat: add 'My Memories' frontend panel with tab switching and delete"
```

---

## Phase 5: 端到端测试 + 上线

### Task 5.1: 启动服务并验证 collection 创建

- [ ] **Step 1: 确保 Docker 服务运行**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && make up
```

- [ ] **Step 2: 启动应用**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn spring-boot:run
```

- [ ] **Step 3: 验证 Milvus collection 创建**

```bash
curl http://localhost:8000  # Attu 管理界面
# 检查 user_memory collection 是否存在
```

或通过健康检查：

```bash
curl http://localhost:9900/milvus/health
# 预期返回 collections 包含 "biz" 和 "user_memory"
```

### Task 5.2: 端到端功能测试

- [ ] **Step 1: 测试记忆提取 + 查询**

```bash
# 发起对话（带 userId）
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "我们公司的K8s集群是1.28版本，使用Istio", "userId": "test-user-01"}'

# 多轮对话累积到 6 对触发提取
# ...

# 发起提问测试记忆召回
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "我们用的K8s是什么版本？", "userId": "test-user-01"}'
# 预期 Agent 调用 recallMemory 获取记忆
```

- [ ] **Step 2: 测试记忆面板 API**

```bash
curl "http://localhost:9900/api/memory/panel?userId=test-user-01"
# 预期返回 {success: true, facts: [...], profiles: [...], preferences: [...]}
```

- [ ] **Step 3: 测试记忆删除**

```bash
# 先获取记忆ID，然后删除
curl -X DELETE "http://localhost:9900/api/memory/{memoryId}?userId=test-user-01"
```

- [ ] **Step 4: 测试开关回退**

将 `application.yml` 中 `memory.enabled` 改为 `false`，重启应用：

```bash
# 验证记忆工具未注册
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "查询我的记忆", "userId": "test-user-01"}'
# 预期 Agent 没有 recallMemory 工具，正常对话不受影响
```

### Task 5.3: 设置 memory.enabled=true 并最终测试

- [ ] **Step 1: 确认配置**

```yaml
memory:
  enabled: true
```

- [ ] **Step 2: 完整构建测试**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn clean install
```

- [ ] **Step 3: Commit 并 Push**

```bash
git add -A
git commit -m "feat: enable memory system with default configuration"
git push origin feature/hybrid-recall-rrf
```
