# SuperBizAgent

> 基于 Spring Boot + AI Agent 的企业级智能问答与运维平台

## 📖 项目简介

企业级智能业务代理系统，包含三大核心子系统：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，支持多策略分块、查询改写、混合检索、Agentic RAG，提供高质量的检索增强生成问答。

### 2. AIOps 智能运维
基于 SupervisorAgent（Planner-Executor-Replanner）的多 Agent 协作系统，实现告警分析、日志查询、根因诊断和报告生成。内置意图路由、LLM-as-Judge 质量评估和 10 场景回归测试用例集。

### 3. 长期记忆（Mem0 风格）
自动从对话中提取用户画像和行为偏好，支持向量检索、冲突检测、衰减淘汰，注入到 System Prompt 实现个性化对话。

## 🚀 核心特性

### 智能对话
- ✅ **RAG 问答**: 向量检索 + 混合召回（BM25 + 向量） + Rerank 重排序 + 流式输出
- ✅ **查询改写**: 4 种策略（prompt_rewrite / hypothetical_answer / detail_abstract / direct）+ Redis 缓存
- ✅ **Agentic RAG**: 多轮检索、问题拆解、结果评估、自动降级
- ✅ **多策略分块**: heading / fixed-size / semantic / parent-child，支持按扩展名覆盖

### AIOps 运维
- ✅ **意图路由**: 基于 qwen-turbo 自动分类请求（告警排查 / 知识检索 / 通用对话），按意图分发到不同 Agent 管道
- ✅ **多 Agent 协作**: SupervisorAgent → Planner → Executor → Replanner 闭环
- ✅ **超时保护**: 可配置超时自动终止 + 基于 LLM 的兜底报告生成
- ✅ **质量评估**: LLM-as-Judge 在线评分（根因准确性 / 证据充分性 / 结构完整性 / 可操作性）
- ✅ **回归测试**: 10 场景测试用例集（含预期根因 + mock 数据）

### 安全与稳定性
- ✅ **API Key 认证**: 请求头验证 + SecurityContext 传递 + 匿名用户隔离
- ✅ **令牌桶限流**: Bucket4j + Caffeine，按端点独立配置
- ✅ **断路器保护**: Resilience4j（DashScope LLM / Embedding / Milvus 搜索）
- ✅ **文件上传加固**: IP 级限流 + 大小校验 + 降级文档重索引

### 会话与存储
- ✅ **Redis + 内存双模**: 会话持久化，故障自动降级到 ConcurrentHashMap
- ✅ **摘要压缩**: 消息对超阈值自动生成摘要，减少上下文窗口压力
- ✅ **长期记忆**: 自动提取画像/偏好 → 向量存储 → 冲突检测 → 衰减淘汰

### 前端
- ✅ **Web 界面**: Gemini 风格单页应用，Markdown 渲染 + 代码高亮 + SSE 流式展示

## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.5.15 | 应用框架 |
| Spring AI | 1.1.2 | AI 抽象层（ChatModel / Embedding） |
| Spring AI Alibaba | 1.1.2.0 | AI Agent 框架（DashScope + SupervisorAgent） |
| DashScope SDK | 2.17.0 | 阿里云 AI 服务（LLM + Embedding + Rerank） |
| Spring AI OpenAI | 1.1.2 | liteLLM 网关 OpenAI 兼容客户端 |
| Milvus SDK | 2.6.10 | 向量数据库客户端 |
| Resilience4j | 2.3.0 | 断路器 + 限流 |
| Bucket4j | 8.10.1 | 令牌桶限流 |
| PDFBox | 3.0.3 | PDF 文本提取 |
| Lombok | 1.18.30 | 代码生成 |

## 🧭 liteLLM 大模型网关（2026-08 新增）

接入 [liteLLM](https://github.com/BerriAI/litellm) 作为 OpenAI 兼容的大模型网关，统一管理模型调用与成本。**DashScope 保留为 fallback，一键切换**。

### 架构

```
SuperBizAgent (Spring Boot :9900)
  ├─ ChatModelFactory / LlmProvider（聊天/AIOps/意图/摘要/改写）
  ├─ VectorEmbeddingService（向量化 /v1/embeddings）
  └─ VectorSearchService（RAG 重排序 /v1/rerank）
        │  litellm.enabled=true 时
        ▼
liteLLM 网关（独立 Docker 容器 :4000）──▶ DashScope API（唯一上游）
```

### 两种模式

| 模式 | 配置 | 行为 |
|------|------|------|
| **直连模式（默认）** | `litellm.enabled: false` | 保持 DashScope 直连，与改造前完全一致 |
| **网关模式** | `litellm.enabled: true` | 所有模型调用经 liteLLM 网关转发 |

### 启用步骤

```bash
# 1. 启动 Docker Desktop，设置网关环境变量
export DASHSCOPE_API_KEY=sk-百炼key        # 上游密钥（网关侧）
export LITELLM_MASTER_KEY=sk-管理密钥       # 网关管理密钥

# 2. 启动 liteLLM 网关（首次自动拉镜像，稍等）
make litellm-up
make litellm-health                          # 健康检查

# 3. 创建虚拟密钥（应用侧调用网关用，从返回 JSON 的 "key" 字段取值）
curl -X POST http://localhost:4000/key/generate \
  -H "Authorization: Bearer $LITELLM_MASTER_KEY" \
  -H "Content-Type: application/json" \
  -d '{"models": ["qwen3-max","qwen-turbo","text-embedding-v4","gte-rerank-v2"]}'

# 4. 打开网关开关（application.yml）
#    litellm.enabled: true
#    litellm.api-key: sk-虚拟密钥（或设环境变量 LITELLM_API_KEY）

# 5. 启动应用（本地无 MCP 端点时设 MCP_CLIENT_ENABLED=false）
make start
```

> ⚠️ 网关模式启用时应用侧**不再需要** `DASHSCOPE_API_KEY`（上游密钥集中在网关容器）；`litellm.enabled=true` 且 `api-key` 未配置时应用会 fail-fast 拒绝启动。

### 配置说明（application.yml）

```yaml
litellm:
  enabled: false                      # true=走 liteLLM 网关，false=DashScope 直连
  base-url: http://localhost:4000     # 网关地址
  api-key: ${LITELLM_API_KEY:sk-litellm-change-me}  # 虚拟密钥
```

模型注册表在 `litellm/config.yaml`（仅网关使用，`model_name` 与 `application.yml` 模型名严格对齐），容器编排在 `litellm.yml`。

### 回滚

把 `litellm.enabled` 改回 `false` 重启即恢复 DashScope 直连，无需改代码。


## 📦 项目结构

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── Main.java                         # 应用入口
│   ├── controller/                       # 控制器层
│   │   ├── ChatController.java           # 统一对话接口（同步 + SSE 流式）⭐
│   │   ├── AuthController.java           # API Key 登录认证
│   │   ├── MemoryController.java         # 长期记忆管理
│   │   ├── FileUploadController.java     # 文件上传 + 向量化
│   │   └── MilvusCheckController.java    # 健康检查
│   ├── service/                          # 服务层
│   │   ├── ChatService.java              # Agent 工厂 + 对话执行 ⭐
│   │   ├── AiOpsService.java             # AIOps 多 Agent 编排 ⭐
│   │   ├── RagService.java               # RAG 问答服务
│   │   ├── SessionManager.java           # 会话管理（Redis + 内存）⭐
│   │   ├── SummaryGenerator.java         # 对话摘要生成
│   │   ├── MemoryManager.java            # 长期记忆管理
│   │   ├── MemoryExtractor.java          # 记忆提取
│   │   ├── MemorySearchService.java      # 记忆向量搜索
│   │   ├── MemoryDecayService.java       # 记忆衰减淘汰
│   │   ├── VectorIndexService.java       # 文档向量化索引
│   │   ├── VectorSearchService.java      # 向量相似度搜索
│   │   ├── VectorEmbeddingService.java   # DashScope Embedding
│   │   ├── DocumentChunkService.java     # 文档分块
│   │   ├── DashScopeLlmClient.java       # LLM 调用客户端
│   │   ├── AgenticRagGuard.java          # Agentic RAG 守护
│   │   ├── chunk/                        # 分块策略
│   │   │   ├── HeadingChunkStrategy.java
│   │   │   ├── FixedSizeChunkStrategy.java
│   │   │   ├── SemanticBoundaryStrategy.java
│   │   │   └── ParentChildStrategy.java
│   │   ├── rewrite/                      # 查询改写策略
│   │   │   ├── PromptRewriteStrategy.java
│   │   │   ├── HypotheticalAnswerStrategy.java
│   │   │   ├── DetailAbstractStrategy.java
│   │   │   └── DirectStrategy.java
│   │   └── parser/                       # 文档解析
│   │       ├── TextDocumentParser.java
│   │       └── PdfDocumentParser.java
│   ├── agent/                            # Agent 模块
│   │   ├── LlmProvider.java              # LLM 服务抽象接口
│   │   ├── DashScopeLlmProvider.java     # DashScope 实现（litellm.enabled=false 时注册）
│   │   ├── LiteLlmProvider.java          # liteLLM 网关实现（litellm.enabled=true 时注册）🆕
│   │   ├── ReactAgentRunner.java         # Agent 执行器（SupervisorAgent 编排）
│   │   ├── tool/                         # Agent 工具集
│   │   │   ├── DateTimeTools.java        # 当前时间
│   │   │   ├── InternalDocsTools.java    # 内部文档检索
│   │   │   ├── QueryMetricsTools.java    # Prometheus 告警查询
│   │   │   ├── QueryLogsTools.java       # 日志查询（mock 模式）
│   │   │   ├── RecallMemoryTool.java     # 长期记忆召回
│   │   │   ├── ForgetMemoryTool.java     # 遗忘记忆
│   │   │   ├── SearchKnowledgeBaseTool.java  # 知识库搜索
│   │   │   ├── DecomposeQuestionTool.java    # 问题拆解
│   │   │   ├── EvaluateSearchResultsTool.java # 搜索结果评估
│   │   │   ├── RefineQueryTool.java      # 查询精炼
│   │   │   └── GetSearchCapabilitiesTool.java # 搜索能力查询
│   │   ├── router/                       # 意图识别路由 🆕
│   │   │   ├── IntentRouter.java         # qwen-turbo 意图分类
│   │   │   ├── IntentCategory.java       # 意图枚举
│   │   │   └── IntentResult.java         # 路由结果 DTO
│   │   ├── eval/                         # LLM-as-Judge 评估 🆕
│   │   │   ├── AIOpsEvaluator.java       # 在线质量评估
│   │   │   ├── AIOpsEvalResult.java      # 评估结果 DTO
│   │   │   ├── EvalDimension.java        # 评估维度枚举
│   │   │   ├── TestCaseLoader.java       # 测试用例加载器
│   │   │   └── TestCaseMeta.java         # 用例元数据 DTO
│   │   └── mock/                         # Mock 数据注入 🆕
│   │       └── MockDataProvider.java     # 测试用 mock 数据源
│   ├── security/                         # 安全模块 🆕
│   │   ├── ApiKeyAuthenticationFilter.java
│   │   ├── ApiKeyAuthenticationToken.java
│   │   ├── ApiKeyAuthManager.java
│   │   └── RateLimitInterceptor.java
│   ├── config/                           # 配置类
│   │   ├── ChatModelFactory.java         # 模型统一构建（网关/直连开关收敛点）🆕
│   │   ├── LiteLlmProperties.java        # liteLLM 网关配置属性 🆕
│   │   ├── ModelProperties.java          # 分层模型配置
│   │   ├── SecurityConfig.java           # Spring Security 配置
│   │   ├── ApiKeyProperties.java         # API Key 配置属性
│   │   ├── RateLimitConfig.java          # 限流配置
│   │   ├── RedisConfig.java              # Redis 配置
│   │   ├── MilvusConfig.java             # Milvus 配置
│   │   ├── WebConfig.java / WebMvcConfig.java  # Web 配置
│   │   ├── AsyncConfig.java              # 异步任务配置
│   │   └── ...                           # 其他属性/配置类
│   ├── dto/                              # 数据传输对象
│   ├── interceptor/                      # 拦截器
│   └── constant/                         # 常量
├── src/main/resources/
│   ├── static/                           # Web 前端（SPA）
│   │   ├── index.html                    # Gemini 风格界面
│   │   ├── app.js                        # 前端逻辑（SSE + Markdown）
│   │   └── styles.css                    # Material 风格样式
│   └── application.yml                   # 应用配置 ⭐
├── src/test/                             # 测试 🆕
│   ├── java/org/example/
│   │   └── AIOpsQualitySmokeTest.java    # 上下文加载冒烟测试
│   └── resources/
│       ├── application-test.yml          # 测试环境配置
│       └── test-cases/                   # 测试用例 fixtures
├── aiops-test-cases/                     # AIOps 回归测试用例集 🆕
│   ├── cpu_high_usage.md                 # 10 个场景定义（YAML frontmatter）
│   ├── memory_high_usage.md
│   ├── disk_high_usage.md
│   ├── service_unavailable.md
│   ├── slow_response.md
│   ├── db_connection_pool_full.md
│   ├── mq_consumer_lag.md
│   ├── k8s_pod_crashloop.md
│   ├── ssl_cert_expiring.md
│   ├── api_timeout_cascade.md
│   └── mock-data/                        # 每场景对应的 mock 数据 JSON
├── aiops-docs/                           # 运维知识库（RAG 文档源）
├── docs/superpowers/specs/               # 设计文档
│   ├── 2026-07-04-session-redis-migration-design.md
│   ├── 2026-07-09-query-rewrite-design.md
│   ├── 2026-07-27-security-auth-design.md
│   └── 2026-07-28-aiops-agent-quality-design.md
├── session/                              # 会话级产出（方案 / 测试报告）
├── litellm/                              # liteLLM 网关配置 🆕
│   └── config.yaml                       # 模型注册表（仅网关使用）
├── litellm.yml                           # liteLLM Docker Compose 编排 🆕
├── .env.example                          # 环境变量模板 🆕
├── vector-database.yml                   # Docker Compose（Milvus + etcd + MinIO）
├── Makefile                              # 一键启动脚本
└── pom.xml
```

## 📡 API 接口

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 同步对话（支持意图路由 + 工具调用 + 多轮对话） |
| POST | `/api/chat_stream` | SSE 流式对话（推荐，实时输出） |
| POST | `/api/ai_ops` | AIOps 告警分析（SSE 流式，SupervisorAgent 编排） |

所有对话接口均内置 **意图路由**：自动识别告警排查、知识检索、通用对话三类请求，分发到对应的 Agent 管道。

### 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/clear` | 清空会话历史 |
| GET  | `/api/chat/session/{sessionId}` | 获取会话详情（含摘要） |

### 认证与安全 🆕

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/login` | API Key 登录（返回 userId） |

### 文件管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload` | 上传文件并自动向量化（限流：10次/分钟，上限 20MB） |
| POST | `/api/upload/reindex-failed` | 重索引降级文档 |
| GET  | `/milvus/health` | Milvus 连接健康检查 |

### 长期记忆 🆕

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/memory/extract` | 手动触发记忆提取 |
| GET  | `/api/memory/search` | 搜索用户记忆 |
| DELETE | `/api/memory/{memoryId}` | 删除指定记忆 |

## ⚙️ 核心配置

```yaml
server:
  port: 9900

# Milvus 向量数据库
milvus:
  host: localhost
  port: 19530

# 阿里云 DashScope
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
    mcp:
      client:
        sse:
          connections:
            tencent-cls:              # 腾讯云 CLS MCP 日志查询

# RAG 配置
rag:
  top-k: 3
  model: qwen3-max
  rerank:                              # Rerank 重排序
    enabled: true
    model: gte-rerank-v2
  hybrid:                              # 混合召回（BM25 + 向量）
    enabled: true
  rewrite:                             # 查询改写
    strategy: direct
    model: qwen-turbo
  agentic:                             # Agentic RAG
    enabled: false
    max-search-rounds: 3

# 文档分块策略
document:
  chunk:
    strategy:
      default-strategy: heading        # heading | fixed-size | semantic | parent-child
      extension-overrides:
        txt: fixed-size

# 会话 Redis 存储
session:
  redis:
    ttl-hours: 24
    fallback-to-memory: true           # Redis 故障自动降级
    summary:
      enabled: true
      trigger-threshold: 10

# 长期记忆（Mem0 风格）
memory:
  enabled: true
  extraction:
    trigger-message-count: 6
    model: qwen-turbo
  search:
    top-k: 5
    score-threshold: 0.6
  decay:
    cron: "0 3 * * *"                  # 每天凌晨 3 点衰减
    decay-factor: 0.1
    min-confidence: 0.3

# AIOps
aiops:
  total-timeout-seconds: 300           # 分析超时上限
  eval:                                # LLM-as-Judge 评估
    enabled: true
    model: qwen-turbo
    min-pass-score: 12
    sample-rate: 1.0

# 意图路由
intent:
  router:
    enabled: true
    model: qwen-turbo
    confidence-threshold: 0.85

# 安全认证
superbiz:
  security:
    enabled: false                     # 全局安全开关
  rate-limit:
    enabled: false                     # 限流开关
    endpoints:
      /api/chat:        { capacity: 30, refill-rate: 5 }
      /api/chat_stream: { capacity: 10, refill-rate: 2 }

# 断路器
resilience4j:
  circuitbreaker:
    instances:
      dashscope-llm:        # LLM 调用熔断
      litellm-llm:          # liteLLM 网关调用熔断
      dashscope-embedding:  # Embedding 调用熔断
      milvus-search:        # Milvus 搜索熔断
  ratelimiter:
    instances:
      file-upload:          # 上传限流: 10次/分钟

# liteLLM 大模型网关 🆕
litellm:
  enabled: false                     # true=走网关，false=DashScope 直连
  base-url: http://localhost:4000
  api-key: ${LITELLM_API_KEY:sk-litellm-change-me}
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key      # 必需（直连模式：应用侧；网关模式：网关侧）
export LITELLM_MASTER_KEY=sk-xxx           # 网关管理密钥（网关模式必需）
export LITELLM_API_KEY=sk-xxx              # 网关虚拟密钥（网关模式必需）
export MCP_CLIENT_ENABLED=false            # 本地无 MCP 端点时关闭
```

## 🚀 快速开始

### 1. 环境准备

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. 启动应用

**方式一：一键启动**
```bash
make init     # 启动 Docker（Milvus + etcd + MinIO）→ 启动应用 → 上传运维文档
```

**方式二：分步启动**
```bash
make up       # 启动 Milvus 等依赖服务
make start    # 后台启动应用（日志输出到 server.log）
make stop     # 停止应用
make down     # 停止所有 Docker 服务
```

**方式三：手动启动**
```bash
docker compose -f vector-database.yml up -d
mvn clean install
mvn spring-boot:run
```

### 3. 访问

```
Web 界面:  http://localhost:9900
Attu 管理: http://localhost:8000
```

### 4. 命令行示例

```bash
# 智能问答（自动路由到 RAG）
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test01","Question":"数据库连接池满了怎么处理？"}'

# 告警排查（自动路由到 AIOps）
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test02","Question":"CPU 飙到 95% 了，帮我分析一下"}'

# 流式对话
curl -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id":"test03","Question":"介绍一下 RAG 的原理"}'

# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.pdf"

# 健康检查
curl http://localhost:9900/milvus/health
```

> 💡 **启用 liteLLM 网关模式**：先 `make litellm-up` 起网关（配置见上「🧭 liteLLM 大模型网关」），再把 `application.yml` 的 `litellm.enabled` 改为 `true` 后重启应用，所有模型调用自动经网关转发。

## 🧪 测试

```bash
# 运行所有测试
mvn test

# 编译验证
mvn clean compile
```

---

**版本**: v1.3.0  
**作者**: chief  
**许可证**: MIT

## 📝 更新日志

### v1.3.0 (2026-08-24)
- 🧭 **liteLLM 大模型网关**: 接入 OpenAI 兼容网关（`litellm.enabled` 开关，DashScope 保留为 fallback）
- 🔌 **模型构建收敛**: 新增 `ChatModelFactory` 统一 8 处模型构建（聊天/AIOps/意图/摘要/改写），网关与直连一键切换
- 🛡️ **双 Provider 二选一**: `LiteLlmProvider`（网关）/ `DashScopeLlmProvider`（直连）条件注册，互不冲突
- 🧠 **Embedding / Rerank 网关化**: `VectorEmbeddingService`（`/v1/embeddings`）、`VectorSearchService`（`/v1/rerank`）支持网关分支
- 🔐 **fail-fast 校验**: 网关模式启动时校验 base-url 与虚拟密钥，避免带病运行
- 📦 **网关部署**: `litellm/config.yaml` + `litellm.yml`（Docker Compose）+ Makefile `litellm-up/down/health` + `.env.example`
- 🧪 **测试增强**: 新增 7 个测试类（属性绑定 / 工厂开关 / 参数透传 / 双模式上下文 / 网关响应解析），全量 36 测试通过

### v1.2.0 (2026-07-28)
- 🧭 **意图识别路由**: 基于 qwen-turbo 自动分类请求意图，分发到 AIOps / RAG / 通用对话管道
- 🎯 **LLM-as-Judge 评估**: AIOps 输出在线质量评分（根因准确性 / 证据充分性 / 结构完整性 / 可操作性）
- 🧪 **AIOps 测试用例集**: 10 场景回归测试（含预期根因 + mock 数据）
- 🔐 **安全认证**: API Key 认证 + Bucket4j 令牌桶限流 + SecurityContext 用户隔离
- 🧠 **长期记忆**: Mem0 风格记忆提取、向量搜索、冲突检测、衰减淘汰
- 🔍 **RAG 增强**: 混合召回（BM25 + 向量）、Rerank 重排序、Agentic RAG、查询改写
- 📝 **会话增强**: Redis 持久化 + 摘要压缩 + 内存降级
- ✂️ **多策略分块**: heading / fixed-size / semantic / parent-child

### v1.1.0 (2026-07-23)
- 🔌 Resilience4j 断路器（LLM / Embedding / Milvus）
- 🧠 Redis 内存降级（SessionManager 自动切换）
- ⏱️ AIOps 超时保护 + 兜底报告
- 📁 文件上传加固（IP 限流 + 大小限制 + 重索引）

### v1.0.0
- 初始版本：RAG 问答 + AIOps 运维 + Web 界面
