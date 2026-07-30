# SuperBizAgent 项目改进方案

> 生成时间：2026-07-22 | 基于 `/grilling` 深度分析

---

## 概述

本文档记录了通过系统化审查发现的 16 个需要改进的领域，按优先级排序：

| 优先级 | 编号 | 改进领域 | 影响范围 |
|--------|------|----------|----------|
| 🔴 P0 | 1 | 自动化测试 | 质量保障 |
| 🔴 P0 | 3 | 安全与认证 | 安全 |
| 🔴 P0 | 2 | 容错与优雅降级 | 可靠性 |
| 🟠 P1 | 5 | AIOps Agent 质量保障 | 核心业务 |
| 🟠 P1 | 11 | LLM 成本控制 | 成本 |
| 🟠 P1 | 13 | 错误处理一致性 | 可维护性 |
| 🟠 P1 | 16 | 框架耦合隔离 | 架构 |
| 🟡 P2 | 4 | 可观测性与监控 | 运维 |
| 🟡 P2 | 7 | 前端韧性 | 用户体验 |
| 🟡 P2 | 8 | 配置与环境管理 | 工程化 |
| 🟡 P2 | 10 | Prompt 管理 | 可维护性 |
| 🟡 P2 | 12 | API 版本化 | 协作 |
| 🟢 P3 | 6 | 数据生命周期与多租户 | 数据治理 |
| 🟢 P3 | 9 | CI/CD 自动化 | 工程化 |
| 🟢 P3 | 14 | 知识库运维 | 核心业务 |
| 🟢 P3 | 15 | Milvus 表结构演进 | 数据治理 |

---

## 🔴 P0 — 必须优先解决

### 1. 自动化测试

**现状**：项目 59 个 Java 源文件，0 个测试。`src/test/` 目录不存在。

**风险**：
- 每一次新功能合入都是盲目的，回归风险持续累积
- 异步操作（`@Async` 内存提取、摘要生成、定时衰减）的时间问题手动测试几乎不可能捕捉
- 混合搜索 + RRF + 重排序的数学逻辑无法验证正确性

**建议方案**：
1. 先建 `src/test/java/org/example/` 目录结构
2. 第一阶段：核心服务集成测试（连接真实/模拟 Milvus + Redis）
   - `VectorSearchServiceTest`：验证 dense + BM25 → RRF → Rerank 管道
   - `MemoryManagerTest`：CRUD + 冲突解决（UPDATE/MERGE/NEW）
   - `SessionManagerTest`：3 层 Redis 状态、滑动窗口淘汰、摘要触发
3. 第二阶段：Agent 工具单元测试
   - 各 Tool 类的输入输出验证
   - `AgenticRagGuard` 轮数计数正确性
4. 第三阶段：API 集成测试（`@SpringBootTest` + `@AutoConfigureMockMvc`）
   - `/api/chat` 非流式对话
   - `/api/chat_stream` SSE 流式输出
   - `/api/ai_ops` AIOps 分析流程
   - `/api/memory/panel` 记忆面板

**依赖**：
- `spring-boot-starter-test`（已有 Spring Boot 依赖，补全即可）
- Milvus 测试可以用 Testcontainers 或嵌入式 Milvus
- Redis 测试可以用 Testcontainers 或嵌入式 Redis

---

### 2. 容错与优雅降级

**现状**：多处外部依赖调用没有统一的容错机制。

**风险**：
- DashScope API 故障 → 用户看到原始异常堆栈
- Milvus 运行时宕机 → 所有搜索请求抛异常
- Redis 不可用 → 聊天完全不可用（会话存储、摘要触发锁、记忆提取锁全部依赖 Redis）
- AIOps Agent 循环无硬性最大轮数限制
- 文件上传无大小/频率限制

**建议方案**：
1. **引入 Resilience4j 断路器**
   ```yaml
   resilience4j:
     circuitbreaker:
       instances:
         dashscope-llm:
           sliding-window-size: 10
           failure-rate-threshold: 50
           wait-duration-in-open-state: 30s
         milvus:
           sliding-window-size: 5
           failure-rate-threshold: 50
           wait-duration-in-open-state: 10s
   ```
   覆盖：`VectorEmbeddingService`、`VectorSearchService`、`DashScopeLlmClient`、`MilvusServiceClient`

2. **Redis 内存回退**
   - `SessionManager` 加 `ConcurrentHashMap` 二级缓存
   - Redis 不可用时自动降级到内存存储（服务重启后丢失，但可用）
   - 加配置开关 `session.redis.fallback-to-memory=true`

3. **AIOps 循环加硬性上限**
   - `AiOpsService` 加 `maxRounds` 参数（默认 10）
   - 超限后由 SupervisorAgent 强制输出报告

4. **文件上传加固**
   - 加文件大小限制（默认 20MB）
   - 加上传频率限制（同 IP 每分钟最多 10 次）

---

### 3. 安全与认证

**现状**：没有任何认证机制。

**风险**：
- CORS 开放所有来源（`allowedOrigins("*")`）
- `userId` 由客户端随意传入，无身份校验
- 任何人可以读取/删除/清空他人的长期记忆数据
- LLM 调用端点无速率限制，可被耗尽 API 配额

**建议方案**：

1. **最简单的方案：API Key 认证**
   ```java
   // 在 application.yml 中配置
   security:
     api-key: ${SUPERBIZ_API_KEY:}
     api-key-header: X-API-Key
   ```
   - 加一个 `AuthenticationInterceptor` 校验请求头
   - 校验失败返回 401

2. **中等方案：Spring Security + API Key**
   - 引入 `spring-boot-starter-security`
   - 自定义 `ApiKeyAuthenticationFilter`
   - userId 从认证上下文中提取，不再由客户端传入

3. **速率限制**
   ```java
   // 用 Bucket4j 或 Guava RateLimiter
   // /api/chat: 30 req/min per user
   // /api/chat_stream: 10 req/min per user
   // /api/ai_ops: 5 req/min per user
   ```
   加 `RateLimitInterceptor`

4. **CORS 收紧**
   - 生产环境把 `allowedOrigins` 改为具体的域名列表

---

## 🟠 P1 — 尽快解决

### 4. 可观测性与监控

**现状**：只有 `LogInterceptor` 记录 HTTP 请求。服务层、Agent 工具、LLM 调用几乎不打日志。

**建议方案**：

1. **引入 Micrometer + Prometheus**
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,metrics,prometheus
   ```
   暴露以下核心指标：
   - `llm_calls_total{model, status}` — LLM 调用次数和成功率
   - `llm_call_duration_seconds{model}` — LLM 调用延迟
   - `milvus_search_duration_seconds` — 向量搜索延迟
   - `agent_loop_rounds_total` — AIOps Agent 循环轮数分布
   - `memory_extraction_total{status}` — 记忆提取次数和成功率

2. **Agent 执行 Trace**
   - 在 `AiOpsService` 每次 Planner 决策和 Executor 反馈时打结构化日志
   ```java
   log.info("[AIOps Trace] Round={} Decision={} Step={} ToolResult={}",
            round, decision, step, resultSummary);
   ```

3. **异步任务监控**
   - `AsyncUncaughtExceptionHandler` 统一处理 `@Async` 方法的异常
   - 记录失败次数、失败原因到指标

4. **健康检查增强**
   - `/actuator/health` 增加 Milvus、Redis、DashScope 连通性检查

---

### 5. AIOps Agent 质量保障

**现状**：Planner-Executor-Replanner 管道的输出质量没有衡量标准。

**建议方案**：

1. **建告警场景测试集**（`aiops-test-cases/`）
   ```
   aiops-test-cases/
   ├── cpu_high_usage.md        # 场景描述 + 预期根因 + 预期修复步骤
   ├── memory_high_usage.md
   ├── disk_high_usage.md
   ├── service_unavailable.md
   ├── slow_response.md
   ├── db_connection_pool_full.md   # 新增场景
   ├── mq_consumer_lag.md           # 新增场景
   ├── k8s_pod_crashloop.md         # 新增场景
   ├── ssl_cert_expiring.md         # 新增场景
   └── api_timeout_cascade.md       # 新增场景
   ```

2. **LLM-as-Judge 评估**
   - 每次改 Agent prompt 后，自动跑测试集
   - 评估维度：根因准确度、修复步骤完备性、报告结构完整性
   - 评分 1-5，低于 3 分的场景标记为回归

3. **意图识别路由**
   - 加一个轻量分类层（调用 qwen-turbo 做意图识别）
   - "我的系统 CPU 高了怎么办" → 路由到 AIOps
   - "什么是 CPU 使用率" → 路由到 RAG Chat
   - "帮我写一个监控脚本" → 路由到普通 Chat

---

### 6. LLM 成本控制

**现状**：token 消耗不可见。AIOps 每次分析可能烧掉数万 token。

**建议方案**：

1. **Token 计数**
   - DashScope API 返回 `usage.input_tokens` 和 `usage.output_tokens`
   - 解析并记录到日志 + Micrometer 指标
   - 每次请求结束时在日志中输出：`[Cost] chatId=xxx tokens=1234(456+778) model=qwen3-max`

2. **AIOps token 上限**
   - `AiOpsService` 加 `maxTotalTokens` 参数（默认 50000）
   - 超出后强制输出当前分析结果

3. **记忆提取速率限制**
   - `memory.extraction.max-per-day: 50`（每用户每天最多 50 次提取）
   - 超出后当天不再触发提取

4. **模型分层明确化**
   | 任务 | 推荐模型 | 当前状态 |
   |------|----------|----------|
   | Chat 对话 | qwen3-max | ✅ 已使用 |
   | AIOps 分析 | qwen3-max | ✅ 已使用 |
   | 记忆提取 | qwen-turbo | ✅ 已使用 |
   | 对话摘要 | qwen-turbo | ❌ 需要改 |
   | 查询重写 | qwen-turbo | ❌ 需要改 |
   | Agentic RAG 评估 | qwen-turbo | ✅ 已使用 |
   | 意图识别（新） | qwen-turbo | 🆕 待实现 |

---

### 7. 错误处理一致性

**现状**：Controller 层各自 try-catch，异步异常静默丢失，工具层错误处理不统一。

**建议方案**：

1. **全局异常处理器**
   ```java
   @RestControllerAdvice
   public class GlobalExceptionHandler {
       // 统一响应格式：{ "errorCode": "MILVUS_UNAVAILABLE", "message": "...", "timestamp": "..." }
   }
   ```

2. **业务异常层次**
   ```
   BizException (RuntimeException)
   ├── ServiceUnavailableException    → 503, 依赖服务不可用
   ├── InvalidInputException          → 400, 参数校验失败
   ├── RateLimitExceededException     → 429, 触发限流
   ├── ResourceNotFoundException      → 404, 数据不存在
   └── AuthenticationException        → 401, 认证失败
   ```

3. **异步异常处理**
   ```java
   @Configuration
   public class AsyncExceptionConfig implements AsyncConfigurer {
       @Override
       public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
           return (ex, method, params) -> {
               log.error("[Async Error] method={} params={}", method.getName(), params, ex);
               meterRegistry.counter("async_errors_total", "method", method.getName()).increment();
           };
       }
   }
   ```

---

### 8. 框架耦合隔离

**现状**：业务代码直接依赖 `ReactAgent`、`SupervisorAgent`、`DashScopeChatModel`。框架是 RC 版本，升级风险高。

**建议方案**：

1. **Agent 抽象层**
   ```java
   public interface AgentRunner {
       AgentResult execute(String systemPrompt, String userMessage, List<ToolSpec> tools);
   }
   ```
   - `ReactAgentRunner` — 当前实现，封装 Spring AI Alibaba ReactAgent
   - 未来：`LangChain4jRunner`、`DirectApiRunner`

2. **LLM Provider 抽象层**
   ```java
   public interface LlmProvider {
       LlmResponse chat(String systemMessage, String userMessage, ChatOptions options);
       LlmStreamResponse chatStream(...);
   }
   ```
   - `DashScopeLlmProvider` — 当前实现
   - 未来：`LiteLlmProvider`、`OpenAiProvider`

3. **原则**：业务代码只依赖接口，不依赖框架具体类

---

## 🟡 P2 — 应该解决

### 9. 前端韧性

**建议方案**：

1. **SSE 自动重连**
   - 监听 `EventSource.onerror`，指数退避重连（1s → 2s → 4s → 8s → max 30s）
   - 重连时通过 URL 参数传 `lastEventId`，后端继续发送未完成的内容

2. **状态管理解耦**
   - 不引入框架，用简单的 PubSub 模式
   ```javascript
   const Store = {
     state: { sessionId, messages, theme, memoryPanelData },
     subscribers: [],
     setState(newState) { /* merge + notify */ }
   };
   ```

3. **错误消息前端转换**
   - 拦截技术异常（包含 `Exception`、`at org.example` 等关键词）
   - 转换为中文友好提示

---

### 10. 配置与环境管理

**建议方案**：

1. **Spring Profile 分离**
   ```
   application.yml          # 公共配置
   application-dev.yml      # 开发环境（模拟数据开启，debug 日志）
   application-prod.yml     # 生产环境（安全配置，INFO 日志）
   ```

2. **LLM 模型名配置化**
   ```yaml
   ai:
     model:
       chat: qwen3-max           # ChatController 主对话
       lightweight: qwen-turbo   # 摘要、提取、评估
       aiops: qwen3-max          # AIOps 分析
       reasoning: qwen3-30b-a3b-thinking-2507  # RAG 推理
   ```

3. **特性开关矩阵文档**（`docs/feature-flags.md`）
   | 开关 | 依赖 | 说明 |
   |------|------|------|
   | `memory.enabled` | Milvus, Redis | 长短期记忆系统 |
   | `rag.agentic.enabled` | `biz` collection 有数据 | Agentic RAG 多轮搜索 |
   | `cls.mock-enabled` | - | CLS 日志模拟 vs MCP 真实 |

---

### 11. Prompt 管理

**建议方案**：

1. **Prompt 外部化**
   ```
   prompts/
   ├── chat/
   │   └── system-prompt.md          # ChatService.buildSystemPrompt()
   ├── aiops/
   │   ├── supervisor-prompt.md      # SupervisorAgent 提示词
   │   ├── planner-prompt.md         # Planner Agent 提示词
   │   └── executor-prompt.md        # Executor Agent 提示词
   ├── memory/
   │   └── extraction-prompt.md      # MemoryExtractor 提取提示词
   └── summary/
       └── summary-prompt.md         # SummaryGenerator 摘要提示词
   ```

2. **Prompt 元数据格式**（YAML frontmatter）
   ```yaml
   ---
   version: 3
   modified: 2026-07-22
   author: chief
   changes: "加了意图识别路由指令"
   model: qwen3-max
   ---
   你是 SuperBizAgent，一个企业级智能运维助手……
   ```

3. **启动时加载到内存** `Map<String, String>`，支持热重载（可选）

---

### 12. API 版本化

**建议方案**：

1. **URL 加版本前缀**
   - 新路径：`/api/v1/chat`、`/api/v1/chat_stream` 等
   - 旧路径保留 3 个月做兼容，返回 `Deprecation: true` 响应头

2. **引入 SpringDoc OpenAPI**
   ```xml
   <dependency>
       <groupId>org.springdoc</groupId>
       <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
   </dependency>
   ```
   - `@Operation`、`@Schema` 注解补充
   - 自动生成 Swagger UI：`/swagger-ui.html`

3. **SSE 协议文档**（`docs/api-sse-protocol.md`）
   ```markdown
   ## SSE 事件类型
   | event | 说明 | 示例 |
   |-------|------|------|
   | content | 文本增量 | `data: {"delta":"你好"}` |
   | tool_call | 工具调用 | `data: {"tool":"queryPrometheusAlerts","args":{}}` |
   | tool_result | 工具返回 | `data: {"result":"..."}` |
   | error | 错误 | `data: {"code":"TIMEOUT","msg":"..."}` |
   | done | 完成 | `data: {"totalTokens":1234}` |
   ```

---

## 🟢 P3 — 计划解决

### 13. 数据生命周期与多租户

**建议方案**：

1. **biz 文档版本化**
   - `metadata` 加 `version`（递增整数）和 `expireAt`（过期时间戳）
   - 上传新版本文档时，旧版本标记 `expired=true`
   - 搜索时默认过滤过期文档

2. **多租户准备**
   - Milvus 使用 Partition Key 按 tenant 物理隔离
   - Redis key 加 tenant 前缀

3. **Redis 内存保护**
   - 会话 key TTL 设为合理值（默认 24h）
   - 全局会话 key 最大数量限制（默认 10000 个）

---

### 14. CI/CD 自动化

**建议方案**：

1. **GitHub Actions 最简流水线**
   ```yaml
   # .github/workflows/ci.yml
   name: CI
   on: [push, pull_request]
   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { java-version: 17 }
         - run: mvn clean compile
   ```

2. **Dependabot 配置**
   ```yaml
   # .github/dependabot.yml
   version: 2
   updates:
     - package-ecosystem: maven
       directory: "/SuperBizAgent-release-2026-05-17"
       schedule: { interval: weekly }
   ```

3. **Dockerfile**（待实现）
   - 多阶段构建：Maven 编译 → 瘦 JRE 运行镜像

---

### 15. 知识库运维

**建议方案**：

1. **扩展 aiops-docs**
   ```
   aiops-docs/
   ├── cpu_high_usage.md          # ✅ 已有
   ├── memory_high_usage.md       # ✅ 已有
   ├── disk_high_usage.md         # ✅ 已有
   ├── service_unavailable.md     # ✅ 已有
   ├── slow_response.md           # ✅ 已有
   ├── db_connection_pool_full.md # 🆕 数据库连接池耗尽
   ├── mq_consumer_lag.md         # 🆕 消息队列积压
   ├── k8s_pod_crashloop.md       # 🆕 K8s Pod CrashLoopBackOff
   ├── ssl_cert_expiring.md       # 🆕 SSL 证书即将过期
   └── api_timeout_cascade.md     # 🆕 API 超时雪崩
   ```

2. **前端反馈按钮**
   - 每个 Chat 回答下方加 👍/👎 按钮
   - 反馈写入 Redis，定期汇总

---

### 16. Milvus 表结构演进

**建议方案**：

1. **Schema 版本管理文档**（`docs/milvus-schema-changelog.md`）
   ```markdown
   ## 当前版本：v1
   - biz collection: id(VarChar), vector(FloatVector 1024dim), content(VarChar), metadata(JSON)
   - user_memory collection: id, user_id, vector(1024dim), content, metadata
   - 索引：IVF_FLAT(nlist=128), SPARSE_INVERTED_INDEX
   ```

2. **迁移流程**
   - 新建 `biz_v2` collection → 后台批量迁移数据 → 修改配置切换到新 collection → 删除旧 collection

---

## 实施建议

### 第一阶段（1-2 周）
1. ✅ 安全认证（P0-3）
2. ✅ 全局异常处理器（P1-13）
3. ✅ 最简测试框架搭建 + 3 个核心测试（P0-1）

### 第二阶段（2-4 周）
4. ✅ 断路器 + Redis 降级（P0-2）
5. ✅ 可观测性（Micrometer + 日志）（P2-4）
6. ✅ LLM 成本追踪（P1-11）
7. ✅ Prompt 外部化（P2-10）

### 第三阶段（4-8 周）
8. ✅ AIOps 测试集 + 评估框架（P1-5）
9. ✅ 框架抽象层（P1-16）
10. ✅ API 版本化 + Swagger（P2-12）
11. ✅ 配置 Profile 分离（P2-8）
12. ✅ 前端 SSE 重连 + 状态管理（P2-7）

### 第四阶段（8-12 周）
13. ✅ CI/CD 流水线（P3-9）
14. ✅ 知识库扩展 + 反馈循环（P3-14）
15. ✅ 数据生命周期管理（P3-6）
16. ✅ Milvus Schema 演进文档（P3-15）
