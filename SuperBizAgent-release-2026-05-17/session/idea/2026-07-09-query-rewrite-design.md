# 查询改写层（Query Rewrite）设计文档

> 版本: v1.0 | 日期: 2026-07-09 | 状态: 待审查

## 1. 问题背景

当前 RAG 管线中，用户的 prompt 被原封不动地传给 `VectorEmbeddingService.generateQueryVector()` 生成向量，然后到 Milvus 进行相似度搜索。当用户的 prompt 不够精确时（过于模糊或过于细节），生成的向量与知识库文档的语义匹配度低，导致召回率不足，影响 LLM 回答的准确性。

### 当前数据流

```
用户 Prompt → RagService.queryStream()
  → VectorSearchService.searchSimilarDocuments(question, topK)
    → denseSearch(question)
      → VectorEmbeddingService.generateQueryVector(question)  // 无改写
        → DashScope text-embedding-v4 → Milvus 搜索
```

## 2. 解决方案

使用**策略模式**在 `RagService` 中新增查询改写层，在生成向量之前对查询文本进行改写优化。提供 4 种可选策略，通过 `application.yml` 配置切换。

### 数据流（改后）

```
用户 Prompt → RagService.queryStream()
  → QueryRewriteService.rewrite(question)           // 新增：查询改写层
    → [策略判断 → LLM改写(可选) → 缓存 → 降级]
  → VectorSearchService.searchSimilarDocuments(rewrittenQuery, topK)
    → denseSearch(rewrittenQuery)                    // 用改写后的 query
      → VectorEmbeddingService.generateQueryVector(rewrittenQuery)
```

## 3. 四种策略

| 策略 | 枚举值 | 描述 | 依赖 LLM |
|------|--------|------|---------|
| 策略1：Prompt 改写 | `prompt_rewrite` | LLM 将用户 prompt 改写为更清晰、更易被检索系统理解的表达 | 是 |
| 策略2：假设答案 | `hypothetical_answer` | LLM 先生成简要回答，再对回答做 embedding 召回 | 是 |
| 策略3：细节/宏观转换 | `detail_abstract` | LLM 判断问题类型后做反向转换（细节→宏观 或 宏观→细节） | 是 |
| 策略4：直接召回 | `direct` | 不做任何改写，直接使用原始 prompt（当前默认行为） | 否 |

**策略选择**：通过 `rag.rewrite.strategy` 配置项指定，默认值为 `direct`（兼容现有行为）。

## 4. 配置设计

在 `application.yml` 的 `rag` 节点下新增 `rewrite` 配置块：

```yaml
rag:
  rewrite:
    strategy: direct           # prompt_rewrite | hypothetical_answer | detail_abstract | direct
    model: qwen-turbo          # 改写用的轻量 LLM
    timeout: 30s               # LLM 调用超时
    retry:
      max-attempts: 3          # 超时类错误最大重试次数
      backoff:
        initial-interval: 5s   # 首次重试间隔
        multiplier: 5          # 指数退避倍数
    cache:
      enabled: true            # Redis 缓存开关
      ttl-hours: 1             # 缓存过期时间（默认 1 小时）
```

## 5. 核心类设计

### 5.1 文件结构

```
src/main/java/org/example/service/
  └── rewrite/
      ├── QueryRewriteStrategy.java          // 策略接口
      ├── PromptRewriteStrategy.java         // 策略1：Prompt 改写
      ├── HypotheticalAnswerStrategy.java    // 策略2：假设答案
      ├── DetailAbstractStrategy.java        // 策略3：细节/宏观转换
      ├── DirectStrategy.java                // 策略4：直接返回原 query
      ├── QueryRewriteService.java           // 协调服务（策略选择、缓存、降级）
      └── QueryRewriteProperties.java        // 配置属性类
```

### 5.2 策略接口

```java
public interface QueryRewriteStrategy {
    /**
     * 改写查询文本
     * @param originalQuery 原始用户问题
     * @return 改写后的文本（用于 embedding）
     */
    String rewrite(String originalQuery);
}
```

### 5.3 实现类概况

| 类名 | 策略 | 核心逻辑 |
|------|------|---------|
| `PromptRewriteStrategy` | 策略1 | 调用 LLM，prompt 要求将问题改写为更清晰的表达 |
| `HypotheticalAnswerStrategy` | 策略2 | 调用 LLM，prompt 要求对问题给出简要回答 |
| `DetailAbstractStrategy` | 策略3 | 调用 LLM，一次调用完成"类型判断 + 反向转换" |
| `DirectStrategy` | 策略4 | 不调用 LLM，直接返回 originalQuery |

前三个策略共享同一个 `DashScopeChatModel`（`qwen-turbo`），由 `QueryRewriteService` 统一创建管理。

### 5.4 协调服务

```java
@Service
public class QueryRewriteService {

    private final QueryRewriteStrategy strategy;
    private final QueryRewriteProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final DashScopeChatModel rewriteModel;

    // 职责：
    // 1. @PostConstruct 根据 rag.rewrite.strategy 创建策略实例
    // 2. 检查 Redis 缓存，命中直接返回
    // 3. 调用策略 rewrite()，处理超时重试 + 降级
    // 4. 异步写入 Redis 缓存
    // 5. 记录每次改写的日志

    public String rewrite(String originalQuery) {
        // 1. direct 策略直接返回
        // 2. 查 Redis 缓存
        // 3. try-catch 调用策略：
        //    - 超时异常 → 指数退避重试（5s→25s→125s，最多3次）
        //    - 业务异常 → 直接降级
        // 4. 降级：log.warn + 返回 originalQuery
        // 5. 成功：异步写 Redis + 返回改写结果
    }
}
```

### 5.5 配置属性类

```java
@ConfigurationProperties(prefix = "rag.rewrite")
public class QueryRewriteProperties {

    private StrategyType strategy = StrategyType.DIRECT;
    private String model = "qwen-turbo";
    private Duration timeout = Duration.ofSeconds(30);
    private Retry retry = new Retry();
    private Cache cache = new Cache();

    enum StrategyType {
        PROMPT_REWRITE,
        HYPOTHETICAL_ANSWER,
        DETAIL_ABSTRACT,
        DIRECT
    }

    static class Retry {
        int maxAttempts = 3;
        Backoff backoff = new Backoff();
    }

    static class Backoff {
        Duration initialInterval = Duration.ofSeconds(5);
        int multiplier = 5;
    }

    static class Cache {
        boolean enabled = true;
        int ttlHours = 1;
    }
}
```

## 6. RagService 改动

仅修改 `queryStream()` 方法，在向量检索前插入一行改写调用：

```java
public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
    // 1. 查询改写（新增）
    String rewrittenQuery = queryRewriteService.rewrite(question);

    // 2. 用改写后的 query 做向量检索（改为使用 rewrittenQuery）
    List<VectorSearchService.SearchResult> searchResults =
        vectorSearchService.searchSimilarDocuments(rewrittenQuery, topK);

    // 3. 构建 prompt（传入改写后的 query + 召回文档）
    String prompt = buildPrompt(rewrittenQuery, context);

    // ... 后续流程不变
}
```

- `VectorSearchService` 和 `InternalDocsTools` **不做任何修改**
- `RagService.buildPrompt()` 参数名调整，逻辑不变

## 7. 异常处理与降级

### 7.1 异常分类

| 异常类型 | 典型场景 | 处理方式 |
|---------|---------|---------|
| 超时类 | 网络抖动、LLM 响应慢、`ReadTimeoutException`、`TimeoutException` | 指数退避重试，最多 3 次 |
| 业务类 | API Key 无效、配额耗尽、HTTP 4xx、返回空内容 | 直接降级，不重试 |
| 缓存类 | Redis 连接失败、序列化异常 | 缓存失败不影响主流程 |

### 7.2 重试策略

```
initial-interval: 5s     → 第1次重试等待 5s
multiplier: 5             → 第2次重试等待 25s (5 × 5)
                          → 第3次重试等待 125s (25 × 5)
max-attempts: 3           → 3次后仍失败则降级
```

### 7.3 降级流程

```
rewrite(originalQuery)
  ├─ 策略 = direct → 直接返回 originalQuery
  ├─ 查 Redis 缓存
  │    ├─ 命中 → 返回缓存值
  │    └─ 未命中/Redis 异常 → 继续
  ├─ 调用 LLM 改写
  │    ├─ 成功 → 异步写 Redis → 返回改写结果
  │    ├─ 超时异常 → 指数退避重试 → 3次后仍失败 → 降级
  │    └─ 业务异常 → 直接降级
  └─ 降级 → log.warn + 返回 originalQuery
```

### 7.4 关键日志

```java
// 成功
log.info("查询改写成功, strategy={}, original={}, rewritten={}", strategy, originalQuery, rewrittenQuery);

// 缓存命中
log.debug("查询改写命中缓存, strategy={}, key={}", strategy, cacheKey);

// 重试
log.warn("查询改写超时, 第{}次重试, 等待{}ms", attempt, backoffMs);

// 降级
log.warn("查询改写降级为 direct, strategy={}, reason={}", strategy, reason);
```

## 8. Redis 缓存设计

### 8.1 缓存键

```
Key:   rag:rewrite:{strategy}:{md5(originalQuery)}
Value: 改写后的文本（String）
TTL:   可配置，默认 1 小时

示例:
  rag:rewrite:prompt_rewrite:a1b2c3d4e5...  → "请说明在生产环境中如何..."
  rag:rewrite:hypothetical_answer:f6g7h8... → "部署步骤包括：1. 准备环境..."
```

策略名包含在 key 中，同一原始问题在不同策略下各自缓存，切换策略后不受旧缓存影响。

### 8.2 读写策略

- **读**：同步读取，Redis 异常时 catch 后返回 null（继续走 LLM 改写）
- **写**：异步写入（`CompletableFuture.runAsync`），失败不影响主流程
- **缓存穿透**：LLM 返回空字符串时不写入缓存，下次请求重新改写

## 9. Bean 装配

### 9.1 依赖关系

```
QueryRewriteProperties  ←── 读取 application.yml rag.rewrite.*
         │
QueryRewriteService ────── 持有 QueryRewriteProperties
         │                 持有 StringRedisTemplate（注入已有 Bean）
         │
         ├── 内部创建 DashScopeChatModel(qwen-turbo) 实例
         │
         ├── PromptRewriteStrategy ── 依赖 DashScopeChatModel
         ├── HypotheticalAnswerStrategy ── 依赖 DashScopeChatModel
         ├── DetailAbstractStrategy ── 依赖 DashScopeChatModel
         └── DirectStrategy ── 无外部依赖
                │
RagService ──── 注入 QueryRewriteService
```

### 9.2 策略实例化

`QueryRewriteService` 在 `@PostConstruct` 中根据配置选择策略：

```java
@PostConstruct
public void init() {
    this.strategy = switch (properties.getStrategy()) {
        case PROMPT_REWRITE      -> new PromptRewriteStrategy(createRewriteChatModel());
        case HYPOTHETICAL_ANSWER -> new HypotheticalAnswerStrategy(createRewriteChatModel());
        case DETAIL_ABSTRACT     -> new DetailAbstractStrategy(createRewriteChatModel());
        case DIRECT              -> new DirectStrategy();
    };
}
```

### 9.3 修改文件清单

| 文件 | 改动类型 |
|------|---------|
| `RagService.java` | 修改：注入 `QueryRewriteService`，`queryStream()` 中调用 `rewrite()` |
| `application.yml` | 修改：新增 `rag.rewrite.*` 配置块 |
| `rewrite/QueryRewriteStrategy.java` | 新增：策略接口 |
| `rewrite/PromptRewriteStrategy.java` | 新增：策略1 实现 |
| `rewrite/HypotheticalAnswerStrategy.java` | 新增：策略2 实现 |
| `rewrite/DetailAbstractStrategy.java` | 新增：策略3 实现 |
| `rewrite/DirectStrategy.java` | 新增：策略4 实现 |
| `rewrite/QueryRewriteService.java` | 新增：协调服务 |
| `rewrite/QueryRewriteProperties.java` | 新增：配置属性 |

## 10. LLM Prompt 设计

### 10.1 策略1 — Prompt 改写

```
你是一个查询优化助手。请将用户的问题改写成更清晰、更易被检索系统理解的
表达方式。

要求：
- 保留原始问题的全部语义
- 补充隐含的上下文和关键术语
- 使用更规范、更具体的表述
- 直接输出改写结果，不要输出任何解释

用户问题：{originalQuery}
改写结果：
```

### 10.2 策略2 — 假设答案

```
请对以下问题给出简要回答。不需要过于详细的解释，但需要覆盖核心要点。

要求：
- 回答控制在 200 字以内
- 涵盖问题的核心知识点
- 直接输出回答内容，不要输出任何前缀和解释

问题：{originalQuery}
回答：
```

### 10.3 策略3 — 细节/宏观判断与转换

```
你是一个查询优化助手。请先判断用户问题的类型，然后进行相应转换。

判断规则：
- 细节问题：包含具体的指标、数值、步骤、工具名、实例名等
- 宏观问题：概念性、方法论、概述性的宽泛问题

转换规则：
- 若是细节问题 → 将其抽象为宏观的方法论问题
- 若是宏观问题 → 将其细化为具体的可操作问题

请按以下格式输出（只输出转换结果，不要解释）：
{转换后的问题}

用户问题：{originalQuery}
```

## 11. 测试策略

### 11.1 单元测试

| 测试对象 | 测试内容 |
|---------|---------|
| `DirectStrategy` | 验证原样返回输入 |
| `PromptRewriteStrategy` | Mock LLM 响应，验证 prompt 模板正确拼接 |
| `HypotheticalAnswerStrategy` | Mock LLM 响应，验证返回内容 |
| `DetailAbstractStrategy` | Mock LLM 响应，验证判断+转换逻辑 |
| `QueryRewriteService` | 验证策略选择、缓存命中/未命中、重试、降级 |

### 11.2 集成测试

| 场景 | 预期行为 |
|------|---------|
| `strategy=direct` | 无 LLM 调用，无缓存写入，返回原始 query |
| `strategy=prompt_rewrite` + 正常 LLM | 返回改写后的 query，Redis 中有缓存 |
| LLM 超时 | 3 次指数退避重试后降级，返回原始 query |
| LLM 返回错误（4xx） | 立即降级，返回原始 query |
| Redis 不可用 | LLM 改写正常进行，缓存写入静默失败 |
| 缓存命中 | 不调用 LLM，直接返回缓存值 |

### 11.3 编译与启动验证

1. `mvn clean install` 确保编译通过
2. `mvn spring-boot:run` 启动应用
3. 调用 RAG 相关端点验证功能正常

## 12. 影响范围

- **无破坏性变更**：默认策略为 `direct`，与现有行为完全一致
- **仅影响 RagService 路径**：`InternalDocsTools` 和 Agent 工具调用路径不受影响
- **VectorSearchService 不变**：接口和调用方无需修改
- **降级保证**：LLM 故障时自动 fallback 到原始 query，不影响 RAG 可用性
