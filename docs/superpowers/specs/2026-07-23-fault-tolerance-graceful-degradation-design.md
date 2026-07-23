# 容错与优雅降级设计

> 日期：2026-07-23 | 分支：feature/hybrid-recall-rrf | 状态：待实现
> 来源：session/idea/2026-07-22-superbizagent-improvement-plan.md P0-2

## 1. 目标

为 SuperBizAgent 引入统一的容错与优雅降级机制，覆盖四大领域：

1. **断路器** — 对 DashScope LLM、DashScope Embedding、Milvus 搜索引入 Resilience4j 断路器，防止级联故障
2. **Redis 内存降级** — SessionManager 在 Redis 不可用时自动切换到 ConcurrentHashMap，保证基本聊天可用
3. **AIOps 循环保护** — Agent 分析循环加硬性上限，避免无限循环耗尽资源
4. **文件上传加固** — 加文件大小限制和 IP 级上传频率限制

## 2. 核心设计决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| 断路器库 | Resilience4j Spring Boot 3 | `resilience4j-spring-boot3:2.2.0`，统一管理断路器+限流 |
| DashScope LLM 断路器位置 | `ChatService.executeChat()` 方法级 | 无法拦截框架内部 HTTP 调用，外层包装 agent 调用 |
| DashScope Embedding 断路器 | `VectorEmbeddingService.embed()` 方法级 | 降级返回零向量 |
| Milvus 搜索断路器 | `VectorSearchService.searchSimilarDocuments()` 方法级 | 降级返回空列表，利用已有 BM25 fallback 链 |
| Redis 降级范围 | 仅 SessionManager | 查询改写缓存、记忆提取锁、摘要锁暂不降级 |
| Redis 降级机制 | ConcurrentHashMap 二级缓存 | 三槽位：summary/history/meta |
| Redis 恢复策略 | 自动恢复 | 每次成功的 Redis 操作自动标记可用 |
| AIOps 总超时 | total-timeout-seconds=300，配置化 | 超时后中断 Agent 线程，独立 LLM 调用生成兜底报告 |
| 文件大小限制 | 20MB | Spring Multipart + 业务层双重校验 |
| 文件上传频率限制 | Resilience4j RateLimiter，IP 粒度 | 每分钟 10 次，超额立即返回 429 |

## 3. 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                     SuperBizAgent 应用                        │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─ 断路器层 (Resilience4j) ──────────────────────────────┐  │
│  │                                                        │  │
│  │  dashscope-llm           dashscope-embedding            │  │
│  │  ChatService              VectorEmbeddingService        │  │
│  │  ┌─────────────────┐     ┌──────────────────────┐      │  │
│  │  │ executeChat()    │     │ embed()               │      │  │
│  │  │   ↓ fallback     │     │   ↓ fallback          │      │  │
│  │  │ 友好错误提示      │     │ 零向量(1024维)         │      │  │
│  │  └─────────────────┘     └──────────────────────┘      │  │
│  │                                                        │  │
│  │  milvus-search                                          │  │
│  │  VectorSearchService                                    │  │
│  │  ┌─────────────────────────┐                           │  │
│  │  │ searchSimilarDocuments()│                           │  │
│  │  │   ↓ fallback            │                           │  │
│  │  │ 空列表 → BM25 降级链     │                           │  │
│  │  └─────────────────────────┘                           │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─ SessionManager 降级 ─────────────────────────────────┐  │
│  │                                                        │  │
│  │  Redis (主)  ←──→  ConcurrentHashMap (兜底)            │  │
│  │       ↑ 故障时自动切换 ↓                                │  │
│  │  withRedisFallback(key, redisOp, memoryOp)             │  │
│  │  redisAvailable 原子标记控制路由                         │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─ AIOps 超时保护 ──────────────────────────────────────┐  │
│  │                                                        │  │
│  │  ExecutorService.submit(supervisorAgent.invoke)        │  │
│  │    └── Future.get(timeout, SECONDS)                     │  │
│  │         ├── 正常返回 → extractFinalReport               │  │
│  │         └── TimeoutException → cancel + forceFinalReport│  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─ 文件上传加固 ────────────────────────────────────────┐  │
│  │                                                        │  │
│  │  RateLimiter (IP粒度, 10/min)                          │  │
│  │    → Multipart max-file-size (20MB)                    │  │
│  │      → 业务层 size 校验                                 │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

## 4. 子方案详设

### 4.1 Resilience4j 断路器

**4.1.1 配置**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      dashscope-embedding:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
      milvus-search:
        sliding-window-size: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
      dashscope-llm:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
```

**4.1.2 覆盖点与降级行为**

| 断路器 | 注解方法 | 降级返回值 | 降级影响 |
|--------|---------|-----------|---------|
| `dashscope-embedding` | `VectorEmbeddingService.embed(String)` | 零向量 + metadata 标记 `needsReindex=true` | 文档入库但语义检索失效，BM25 仍可命中；提供重索引端点恢复 |
| `milvus-search` | `VectorSearchService.searchSimilarDocuments(...)` | `Collections.emptyList()` | 搜索退化为纯 BM25（已有 fallback 链） |
| `dashscope-llm` | `ChatService.executeChat(String, String)` | `ChatResponse.error("AI 服务暂时不可用...")` | 友好文案，保护 API 配额 |

**4.1.3 依赖**

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

**4.1.5 重索引恢复端点**

Embedding 恢复后，需要将标记了 `needsReindex=true` 的文档重新向量化：

```
POST /api/upload/reindex-failed

逻辑：
1. 查询 Milvus biz collection，过滤 metadata.needsReindex == true
2. 逐条重新调用 embedding API 获取向量
3. 更新 Milvus 中的向量字段，并将 needsReindex 改为 false
4. 返回 { "total": N, "success": N, "failed": 0 }
```

| 设计点 | 说明 |
|--------|------|
| 触发方式 | 手动调用（运维或前端批量操作），非自动 |
| 幂等性 | 重复调用安全，已修复的文档不会重复处理 |
| 限流 | 内部依次逐条 embedding，不并发打满 API |
| 响应格式 | { "total": 100, "success": 95, "failed": 5, "errors": [...], "warning": "..." } |

**4.1.6 关键设计点**

- Embedding 降级返回零向量 + 标记 `needsReindex=true`：上传不中断，文档通过 BM25 仍可被检索到；上传响应中带 warning 提示用户；后续可通过 `/api/upload/reindex-failed` 一键重新向量化
- Milvus 搜索降级返回空列表：`VectorSearchService.hybridSearch()` 已对稀疏路径失败做 fallback，空 dense 结果会使搜索自动退化为纯 BM25 + 重排序
- LLM 降级消息明确告知用户"熔断保护 + 预计恢复时间"，不暴露技术细节
- Half-open 状态只放行少量请求（3次 / 2次），避免刚恢复就被打垮

### 4.2 Redis 会话内存降级

**4.2.1 数据结构**

```java
// SessionManager 新增成员
private final ConcurrentHashMap<String, String> memoryStore = new ConcurrentHashMap<>();
private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
// memoryStore 中的 key 格式与 Redis 完全一致:
//   session:{id}:summary
//   session:{id}:history
//   session:{id}:meta
```

**4.2.2 操作模板**

```java
private <T> T withRedisFallback(String key, Supplier<T> redisOp, Function<String, T> memoryOp) {
    if (!redisAvailable.get()) {
        return memoryOp.apply(key);
    }
    try {
        T result = redisOp.get();
        redisAvailable.set(true);  // 成功则恢复标记
        return result;
    } catch (RedisConnectionFailureException | RedisTimeoutException e) {
        log.warn("[SessionManager] Redis 不可用，降级到内存存储 (key={}): {}", key, e.getMessage());
        redisAvailable.set(false);
        return memoryOp.apply(key);
    }
}
```

**4.2.3 各操作降级映射**

| 原 Redis 操作 | 降级操作 | 说明 |
|--------------|---------|------|
| `getOrCreateSession(id)` | `memoryStore.get(key)` → 不存在则初始化空 SessionInfo | 首次降级时历史为空 |
| `addMessage(id, msg)` | `memoryStore.put(key, json)` | JSON 序列化写入内存 |
| `getHistoryOnly(id)` | `memoryStore.get(key)` → JSON 反序列化 | 一致的数据格式 |
| `getSessionMeta(id)` | `memoryStore.get(key)` → JSON 反序列化 | 一致的数据格式 |
| `clearSession(id)` | `memoryStore.remove(key)` | 三个槽位全部清除 |
| `updateSummary(id, summary)` | `memoryStore.put(key, summary)` | 摘要数据 |

**4.2.4 配置开关**

```yaml
session:
  redis:
    fallback-to-memory: true   # 可关闭纯 Redis 模式
```

**4.2.5 恢复机制**

每次成功的 Redis 操作自动将 `redisAvailable` 置回 `true`，无需手动干预。只需一次 Redis 成功响应，后续请求自动切回 Redis。

**4.2.6 降级代价与缓解**

| 代价 | 缓解措施 |
|------|---------|
| 服务重启后内存数据丢失 | 日志中记录降级通知，运维可感知 |
| 多实例部署不同步 | 对于单实例部署无影响；多实例场景可后续升级为 Hazelcast |
| 内存无限增长 | 降级模式下只保留最近 6 对消息（与 Redis 滑动窗口规则一致） |

### 4.3 AIOps Agent 超时保护

**技术约束**：`SupervisorAgent.invoke()` 是单次阻塞调用，Planner-Executor 循环完全在框架内部控制，无法从外部逐轮注入计数器。因此采用**总超时 + 独立兜底 LLM 调用**方案。

**4.3.1 配置**

```yaml
aiops:
  total-timeout-seconds: 300  # Agent 分析总超时，默认 5 分钟
```

**4.3.2 流程改造**

```
当前 (有风险):
  executorService.submit(() -> supervisorAgent.invoke(taskPrompt))
    └── 框架内部循环（无上限，最长跑满 SSE 超时 10 分钟）

改造后:
  Future<OverAllState> future = executor.submit(() -> supervisorAgent.invoke(...))
  try:
    state = future.get(totalTimeoutSeconds, SECONDS)
    return extractFinalReport(state)  // 正常完成
  catch TimeoutException:
    future.cancel(true)               // 中断 Agent 线程
    return forceFinalReport(...)       // 独立 LLM 调用生成兜底报告
```

**4.3.3 核心代码**

```java
public String executeAiOpsAnalysis(String userInput) {
    // ... 构建 SupervisorAgent ...
    
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Optional<OverAllState>> future = executor.submit(() -> {
        try {
            return supervisorAgent.invoke(taskPrompt);
        } catch (GraphRunnerException e) {
            logger.error("AIOps Agent 执行异常", e);
            return Optional.empty();
        }
    });
    
    try {
        Optional<OverAllState> state = future.get(totalTimeoutSeconds, TimeUnit.SECONDS);
        executor.shutdownNow();
        
        if (state.isPresent()) {
            Optional<String> report = extractFinalReport(state.get());
            if (report.isPresent()) {
                return report.get();
            }
        }
        // state 为空或提取失败 → 兜底
        return forceFinalReport(chatModel, userInput);
        
    } catch (TimeoutException e) {
        logger.warn("[AIOps] 分析超时 ({}秒)，强制终止并生成兜底报告", totalTimeoutSeconds);
        future.cancel(true);
        executor.shutdownNow();
        return forceFinalReport(chatModel, userInput);
    }
}

private String forceFinalReport(DashScopeChatModel chatModel, String originalInput) {
    String forcePrompt = """
        你是一个企业级 SRE。之前的自动化分析流程因超时被中断。
        请基于以下原始告警信息，结合你的专业知识，生成一份
        简要的告警分析报告。
        
        原始告警信息：
        %s
        
        请按以下格式输出：
        # 告警分析报告（超时终止 - 基于知识推断）
        ## 告警概述
        ## 可能的根因分析（标注为"推断"而非确认）
        ## 建议的排查步骤
        ## 重要提醒：本报告因自动化分析超时而基于专家知识推断
          生成，未经过完整的工具调用验证，建议人工介入排查。
        """.formatted(originalInput);
    
    return chatModel.call(forcePrompt);
}
```

**4.3.4 关键设计点**

- 超时默认 300 秒（5 分钟），与 DashScope LLM 180 秒超时留有余量
- 超时后 `future.cancel(true)` 强制中断 Agent 线程，避免资源泄漏
- `forceFinalReport` 是一次性的独立 LLM 调用（不带工具），不会被 Agent 循环拖住
- 兜底报告明确标注"基于知识推断"和"超时终止"，用户可区分正常报告和兜底报告
- 如果 Agent 正常完成但 state 为空（异常场景），同样走 forceFinalReport 兜底

### 4.4 文件上传加固

**4.4.1 配置**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB        # 单文件大小上限
      max-request-size: 25MB     # 单次请求总大小上限

resilience4j:
  ratelimiter:
    instances:
      file-upload:
        limit-for-period: 10      # 每周期 10 次
        limit-refresh-period: 1m  # 周期 1 分钟
        timeout-duration: 0ms     # 不排队，超额立即拒绝
```

**4.4.2 三层保护**

| 层级 | 机制 | 超限响应 | 作用 |
|------|------|---------|------|
| 前端 | 已有 JS 10MB 检查 | alert 提示 | 第一道防线，减少无效请求 |
| 框架层 | `spring.servlet.multipart.max-file-size` | 413 Payload Too Large | Spring 框架级限制，后端兜底 |
| 应用层 | Resilience4j RateLimiter（IP 粒度） | 429 Too Many Requests | 频率控制，防止滥用 |
| 业务层 | 控制器内显式 size 校验 | 400 + 中文错误提示 | 友好的用户提示 |

**4.4.3 IP 限流的 IP 获取优先级**

```java
private String getClientIp(HttpServletRequest request) {
    // 1. X-Forwarded-For（代理/负载均衡场景）
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isEmpty()) {
        return xff.split(",")[0].trim();
    }
    // 2. X-Real-IP
    String realIp = request.getHeader("X-Real-IP");
    if (realIp != null && !realIp.isEmpty()) {
        return realIp;
    }
    // 3. 直连 IP
    return request.getRemoteAddr();
}
```

**4.4.4 关键设计点**

- `timeout-duration: 0ms`：不排队等待，超额立即返回 429，用户体验好
- IP 限流不在多实例间共享（内存级），但作为第一道防线足够有效
- 前端已有的 10MB 检查保留不做修改，减少无用请求到达后端
- `FileUploadConfig` 新增 `maxSize` 和 `maxRequestsPerMinute` 配置属性

## 5. 文件变更清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `pom.xml` | 新增 `resilience4j-spring-boot3:2.2.0` 依赖 |
| 修改 | `src/main/resources/application.yml` | 新增断路器、限流、文件大小、AIOps 上限配置 |
| 修改 | `VectorEmbeddingService.java` | 加 `@CircuitBreaker` + fallback（零向量+needsReindex标记） |
| 修改 | `VectorIndexService.java` | 写入 Milvus 时支持 metadata needsReindex 标记 |
| 修改 | `VectorSearchService.java` | 加 `@CircuitBreaker` + fallback 方法 |
| 修改 | `ChatService.java` | 加 `@CircuitBreaker` + fallback 方法 |
| 修改 | `SessionManager.java` | 加 `ConcurrentHashMap` 二级存储 + `withRedisFallback` |
| 修改 | `AiOpsService.java` | 加超时控制 + forceFinalReport 兜底逻辑 |
| 修改 | `FileUploadController.java` | 加 RateLimiter + size 校验 + IP 获取 + `POST /api/upload/reindex-failed` 端点 |
| 修改 | `FileUploadConfig.java` | 新增 `maxSize`、`maxRequestsPerMinute` 属性 |

## 6. 测试策略

### 6.1 单元测试

| 测试类 | 测试场景 |
|--------|---------|
| `SessionManagerTest` | Redis 不可用时降级到内存；Redis 恢复后自动切回；内存存储基本 CRUD |
| `AiOpsServiceTest` | 正常完成提取报告；超时后 forceFinalReport 调用；异常 state 兜底逻辑 |
| `FileUploadConfigTest` | 大小/频率配置绑定校验 |

### 6.2 集成测试

| 测试类 | 测试场景 |
|--------|---------|
| `CircuitBreakerIntegrationTest` | 模拟 DashScope/Milvus 连续失败 → 熔断打开 → half-open → 恢复 |

### 6.3 手动验证

| 场景 | 验证方法 |
|------|---------|
| Redis 降级 | 停止 Redis，发送聊天请求，确认返回正常（内存模式） |
| AIOps 超时 | 将 timeout 设为 1 秒，确认超时后返回兜底报告而非堆栈 |
| 文件上传限流 | 1 分钟内连续上传 11 次，第 11 次返回 429 |
| 文件大小限制 | 上传 25MB 文件，确认被拒绝 |
| LLM 熔断 | 使用错误 API Key 发送连续请求，确认熔断后返回友好提示 |

## 7. 风险与边界

### 7.1 不在本次范围

- 全局异常处理器（P1-13，独立改进项）
- 可观测性/Micrometer（P2-4，独立改进项）
- Redis 查询改写缓存降级、记忆提取锁降级（仅做 SessionManager）
- SSE 流式端点断路器（stream 场景 TimeLimiter 更合适，后续迭代）

### 7.2 已知限制

| 限制 | 影响 | 后续计划 |
|------|------|---------|
| `dashscope-llm` 断路器在方法级而非调用级 | 无法区分"偶尔一次超时"和"连续失败"，粒度较粗 | Actuator 上线后用指标细化 |
| 内存 Session 存储无 TTL | 降级场景下长期运行可能 OOM | 降级模式保留滑动窗口限制（最多 6 对消息） |
| 文件上传限流为内存级 | 多实例部署时各自计数，不共享 | 可后续升级为 Redis 计数 |

### 7.3 回滚方案

- Resilience4j 配置可以实时关闭：`resilience4j.circuitbreaker.instances.*.register-health-indicator: false` + 移除注解即可
- `session.redis.fallback-to-memory: false` 可以关闭内存降级
- AIOps 超时秒数设为极大值实际上等于关闭限制
