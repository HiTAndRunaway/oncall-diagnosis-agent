# SuperBizAgent

基于 **Spring Boot 3.2 + Spring AI Alibaba Agent Framework** 的企业级智能业务代理系统，集 RAG 智能问答、AIOps 多 Agent 运维诊断、Mem0 风格长期记忆于一体，并提供 API 版本化、安全认证、限流熔断与 CI/CD 交付能力。

> 源码目录：`SuperBizAgent-release-2026-05-17/`（仓库根为文档与 CI 工作区）。

## 核心能力

| 能力 | 描述 |
|------|------|
| **RAG 智能问答** | 文档上传 → 多策略分块 → Embedding → Milvus 向量库 → 混合检索（BM25 + 向量双路召回 + RRF 融合 + DashScope Rerank 重排）→ LLM 生成答案 |
| **查询改写** | 4 种策略（prompt_rewrite / hypothetical_answer / detail_abstract / direct），支持 Redis 结果缓存与超时重试 |
| **Agentic RAG** | Agent 在 ReAct 循环中自主编排：问题拆解 → 多轮检索 → 相关性评估 → 改写重试，带护栏（轮次 / 阈值 / 超时 / 降级） |
| **AIOps 智能运维** | SupervisorAgent 编排 Planner-Executor 闭环，自动生成告警分析报告；超时保护 + LLM 兜底报告 + LLM-as-Judge 质量评估 |
| **意图路由** | 基于 qwen-turbo 自动分类请求（告警排查 / 知识检索 / 通用对话），分发到不同 Agent 管道 |
| **长期记忆 (Mem0)** | 自动提取 FACT / PROFILE / PREFERENCE 三类记忆，向量存储 + 冲突解决 + 置信度衰减 + TTL 过期清除 |
| **安全与稳定性** | API Key 认证（Spring Security）+ Bucket4j 令牌桶限流 + Resilience4j 断路器（LLM / Embedding / Milvus） |
| **会话管理** | Redis 三层会话存储（summary / history / meta），Redis 故障自动降级内存，超阈值自动摘要压缩 |
| **API 版本化** | `/api/v1/*` 全新 V1 接口（SpringDoc OpenAPI 注解），旧路径 `/api/*` 由 legacy 控制器 301 重定向 |
| **Prompt 管理** | 启动时加载 `prompts/**/*.md` 模板（Mustache 渲染 + YAML frontmatter + 中英双语） |
| **分层模型配置** | chat / aiops / lightweight / reasoning / rewrite 各场景独立模型与参数 |
| **liteLLM 大模型网关** | 接入 OpenAI 兼容网关统一模型调用与成本管理，DashScope 保留为 fallback，`litellm.enabled` 一键切换 |
| **工程化交付** | Dockerfile 多阶段构建 + GitHub Actions CI + GHCR 镜像推送 + Dependabot 依赖更新 |
| **IDE 极客风格前端** | 深色 IDE 风格界面（Activity Bar + Tabs + Status Bar），3 套主题（VS Code Dark+ / One Dark Pro / SynthWave '84），含登录页 |

## 技术栈

```
Java 17  |  Spring Boot 3.5.15  |  Spring AI 1.1.2  |  Spring AI Alibaba 1.1.2.0  |  DashScope (LLM + Embedding + Rerank)
Spring AI OpenAI 1.1.2  |  liteLLM 网关（OpenAI 兼容，可选）  |  Milvus 2.6 (milvus-sdk-java 2.6.10)  |  Redis 7
SSE 流式输出 (SseEmitter + WebFlux Flux)  |  Spring Security  |  Resilience4j 2.3 (断路器)  |  Bucket4j 8.10 + Caffeine (令牌桶限流)
PDFBox 3.0.3  |  jmustache 1.16 (Prompt 模板)  |  SpringDoc OpenAPI 2.8  |  Lombok 1.18.30
```

## 快速开始

### 1. 环境准备

- JDK 17+
- Maven 3.9+
- Docker（推荐，用于 Milvus + Redis）
- 阿里云 DashScope API Key（`DASHSCOPE_API_KEY`）

### 2. 设置 API Key

```bash
export DASHSCOPE_API_KEY=your-dashscope-api-key
```

### 2.1 启用 liteLLM 网关模式（可选）

默认 `litellm.enabled=false`（DashScope 直连）。启用网关模式：

```bash
# 网关侧环境变量
export DASHSCOPE_API_KEY=sk-百炼key        # 上游密钥（集中在网关容器）
export LITELLM_MASTER_KEY=sk-管理密钥       # 网关管理密钥

# 启动 liteLLM 网关（项目目录下执行）
cd SuperBizAgent-release-2026-05-17
make litellm-up                            # docker-compose -f litellm.yml up -d
make litellm-health                        # 健康检查

# 创建虚拟密钥（从返回 JSON 的 "key" 字段取值）
curl -X POST http://localhost:4000/key/generate \
  -H "Authorization: Bearer $LITELLM_MASTER_KEY" \
  -H "Content-Type: application/json" \
  -d '{"models": ["qwen3-max","qwen-turbo","text-embedding-v4","gte-rerank-v2"]}'

# application.yml 开启：litellm.enabled: true（api-key 填虚拟密钥或设 LITELLM_API_KEY）
```

回滚：`litellm.enabled` 改回 `false` 重启即恢复直连。模型注册表在 `litellm/config.yaml`（仅网关使用）。

### 3. 启动基础设施

```bash
# 启动 Milvus + etcd + MinIO + Attu + Redis
docker compose -f vector-database.yml up -d

# Attu 管理界面: http://localhost:8000
# Milvus gRPC: localhost:19530
# Milvus REST: localhost:9091
# Redis: localhost:6379
```

### 4. 构建与运行

```bash
cd SuperBizAgent-release-2026-05-17
mvn clean install
mvn spring-boot:run
```

应用运行在 **http://localhost:9900**。

### 5. 一键初始化（Makefile，在项目目录下执行）

```bash
cd SuperBizAgent-release-2026-05-17
make init     # 启动 Docker → 启动应用 → 上传 aiops-docs 知识库文档
make up       # 仅启动 Docker（Milvus + etcd + MinIO + Attu）
make start    # 后台启动应用（日志输出到 server.log）
make upload   # 上传 aiops-docs 文档到向量库
make stop && make down   # 停止应用与所有 Docker 服务
```

### 6. 健康检查

```bash
curl http://localhost:9900/api/v1/health
curl http://localhost:9900/api/v1/milvus/health
```

### 7. 命令行示例

```bash
# 智能问答（自动路由：告警排查 → AIOps / 知识检索 → RAG / 其他 → 通用对话）
curl -X POST http://localhost:9900/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test01","Question":"数据库连接池满了怎么处理？"}'

# 流式对话（SSE）
curl -N -X POST http://localhost:9900/api/v1/chat_stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"Id":"test02","Question":"介绍一下 RAG 的原理"}'

# AIOps 告警分析（SSE，多 Agent 编排）
curl -N -X POST http://localhost:9900/api/v1/ai_ops

# 上传文档并向量化
curl -X POST http://localhost:9900/api/v1/upload -F "file=@document.pdf"
```

### 8. Docker 部署（生产）

```bash
cd SuperBizAgent-release-2026-05-17
docker build -t superbiz-agent .
docker run -d -p 9900:9900 -e DASHSCOPE_API_KEY=your-key superbiz-agent
```

CI 流水线（`.github/workflows/ci.yml`）在 push 到 master 时自动构建并推送镜像至 GHCR（`ghcr.io/hitandrunaway/oncall-diagnosis-agent`）。

## API 接口（v1 版本化）

> 旧路径 `/api/chat`、`/api/ai_ops`、`/api/upload`、`/api/login`、`/api/memory/panel`、`/milvus/health` 等均 301 重定向至 `/api/v1/*`。

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话（内置意图路由 + 工具调用 + 记忆注入） |
| POST | `/api/v1/chat_stream` | SSE 流式对话（实时输出，事件协议见 `docs/api-sse-protocol.md`） |
| POST | `/api/v1/chat/clear` | 清空会话历史 |
| GET  | `/api/v1/chat/session/{sessionId}` | 查询会话信息（含摘要） |

### AIOps

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/ai_ops` | AIOps 告警分析（SSE 流式，10 分钟超时，多 Agent 编排 + 质量评估） |

### 认证与安全

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/login` | API Key 登录（返回 userId；`superbiz.security.enabled=true` 时生效） |

### 文件与文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/upload` | 上传文件并自动向量化（txt/md/pdf，上限 20MB，限流 10 次/分钟） |
| POST | `/api/v1/upload/reindex-failed` | 重索引降级文档 |

### 长期记忆

| 方法 | 路径 | 说明 |
|------|------|------|
| GET    | `/api/v1/memory/panel` | 记忆面板数据（按 FACT / PROFILE / PREFERENCE 分组） |
| DELETE | `/api/v1/memory/{memoryId}` | 删除单条记忆 |
| DELETE | `/api/v1/memory/clear` | 清空当前用户全部记忆 |

### 健康与文档

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/health` | 应用健康检查 |
| GET | `/api/v1/milvus/health` | Milvus 连接性检查 |
| GET | `/api/v1/docs/ui` | Swagger UI（SpringDoc OpenAPI） |
| GET | `/api/v1/docs/json` | OpenAPI JSON 文档 |

### 请求体格式

```json
{
  "Id": "session-abc123",
  "Question": "当前系统有哪些活跃告警？"
}
```

（`Id` / `Question` 均兼容小写别名 `id` / `question`。）

## 架构

### 1. RAG 检索增强生成

```
文件上传                              用户查询
   │                                      │
   ▼                                      ▼
DocumentParser ─策略──► TXT/MD/PDF 解析   QueryRewriteService ─策略──► 查询改写
   │                                      │
   ▼                                      ▼
ChunkStrategyFactory ─策略──► 分块        VectorSearchService ─混合检索──►
   │                              ┌─ Dense Vector (L2, text-embedding-v4)
   ▼                              ├─ BM25 Sparse (IP, chinese_analyzer)
VectorEmbedding ──► Milvus biz     ├─ RRF 融合 (bm25-weight / vector-weight / rrf-k)
   collection                      └─ DashScope Rerank (gte-rerank-v2)
                                        │
                                        ▼
                                   Top-K 结果 → Agent/LLM 生成答案
```

- **分块策略**：heading（标题拆分，默认）/ fixed-size（固定大小）/ semantic（语义边界）/ parent-child（small-to-big），支持按扩展名覆盖（如 `txt: fixed-size`）。
- **解析策略**：`TextDocumentParser`（TXT/MD）、`PdfDocumentParser`（PDFBox）。
- **Agentic RAG**（`rag.agentic.enabled: true`）：ReAct 循环中自主编排 `decomposeQuestion → searchKnowledgeBase → evaluateSearchResults → refineQuery → 综合答案`；护栏：最大 3 轮检索 / 相关性阈值 0.6 / 60s 超时 / 自动降级（use_best）。关闭后完全回退传统 RAG。

### 2. AIOps 多 Agent 运维

```
用户请求
   │
   ▼
IntentRouter (qwen-turbo 意图分类)
   ├─ ALERT_DIAGNOSIS      ──► AiOpsService（多 Agent 编排）
   ├─ KNOWLEDGE_RETRIEVAL  ──► ReactAgent（提示词引导优先检索知识库）
   └─ GENERAL_CHAT         ──► ReactAgent（标准工具集）
                                    │
                     SupervisorAgent（顶层调度）
                        ├─ PlannerAgent   ── 分解告警 → 规划步骤
                        └─ ExecutorAgent  ── 执行工具调用 → 结构化反馈
                                    │
                             循环直到 decision=FINISH
                                    │
                                    ▼
                      Markdown 告警分析报告（活跃告警表 → 根因分析 → 修复步骤 → 结论）
                                    │
                                    ▼
                      AIOpsEvaluator (LLM-as-Judge) 异步评分
                      根因准确性 / 证据充分性 / 结构完整性 / 可操作性
```

- 超时保护：`aiops.total-timeout-seconds: 300`，超时自动终止并生成 LLM 兜底报告。
- 质量评估：`aiops.eval.enabled: true`，qwen-turbo 四维度评分，`min-pass-score: 12`。
- 回归测试：`aiops-test-cases/` 10 个场景用例（YAML frontmatter 定义预期根因 + `mock-data/` 对应 JSON），由 `TestCaseLoader` 加载。

### 3. 长期记忆系统（Mem0 风格）

```
用户对话 ──► SessionManager.addMessage()
   │
   ▼ (trigger-message-count=6 时触发)
MemoryExtractor.extractAsync()（@Async 异步，不阻塞对话）
   │
   ▼
DashScope LLM 提取记忆 + 冲突解决（NEW / MERGE / UPDATE）
   │
   ▼
MemoryManager.upsert() ──► Milvus user_memory Collection
   │
   ▼
系统提示词注入 ──► ChatService.buildSystemPrompt()（画像/偏好区块）
   │
   ▼
Agent 工具：RecallMemoryTool / ForgetMemoryTool

后台任务：
MemoryDecayService（每天 03:00）──► 置信度衰减 → 低于阈值自动删除
TTL：FACT 永不过期 / PROFILE 90 天 / PREFERENCE 30 天
```

### 4. Agent 执行抽象

框架相关的 Agent 构建与执行被隔离在 `agent/` 层：

- **`AgentRunner`**（接口）→ **`ReactAgentRunner`**（Spring AI Alibaba 实现）：封装 ReactAgent / SupervisorAgent 的同步、流式与多 Agent 编排三种执行模式。
- **`LlmProvider`**（接口）→ **`DashScopeLlmProvider`**（直连模式）/ **`LiteLlmProvider`**（网关模式，二选一条件注册）：封装模型创建与调用，`chat()` 带 `@CircuitBreaker` 熔断与降级。
- **`ChatModelFactory`**：统一构建 ChatModel（`litellm.enabled` 开关决定返回 DashScope 或 OpenAI 兼容模型），收敛聊天 / AIOps / 意图 / 摘要 / 改写各处的模型构建。
- **`PromptManager`**：统一 Prompt 渲染入口，配合 `prompts/zh/**` 目录模板（chat / aiops / agentic-rag / intent / memory / rewrite / summary / eval）。

### 5. liteLLM 大模型网关（2026-08 新增）

```
SuperBizAgent (Spring Boot :9900)
  ├─ ChatModelFactory / LlmProvider（聊天/AIOps/意图/摘要/改写）
  ├─ VectorEmbeddingService（向量化 /v1/embeddings）
  └─ VectorSearchService（RAG 重排序 /v1/rerank）
        │  litellm.enabled=true 时
        ▼
liteLLM 网关（独立 Docker 容器 :4000）──▶ DashScope API（唯一上游）
```

- **双模式**：`litellm.enabled=false`（默认）DashScope 直连；`true` 全部模型调用经 liteLLM 网关转发，成本与日志集中在网关侧。
- **配置**：`litellm/config.yaml` 模型注册表（`model_name` 与应用模型名严格对齐）、`litellm.yml` 容器编排（Docker Compose + healthcheck）、`application.yml` 的 `litellm.*` 连接配置（地址 / 虚拟密钥 / 开关）。
- **命令**：`make litellm-up` / `make litellm-down` / `make litellm-health`（弱依赖，不随 `make up` 启动）。

## 项目结构

```
SuperBizAgent-release-2026-05-17/
├── src/main/java/org/example/
│   ├── Main.java                          # Spring Boot 入口（启用 ModelProperties/DashScopeApiProperties/PromptProperties）
│   ├── agent/
│   │   ├── AgentRunner.java               # Agent 执行抽象接口
│   │   ├── ReactAgentRunner.java          # ReactAgent / SupervisorAgent 实现 ⭐
│   │   ├── LlmProvider.java               # LLM 调用抽象接口
│   │   ├── DashScopeLlmProvider.java      # DashScope 实现（litellm.enabled=false 时注册）
│   │   ├── LiteLlmProvider.java           # liteLLM 网关实现（litellm.enabled=true 时注册）🆕
│   │   ├── tool/                          # Agent 工具集（@Tool 方法工具）
│   │   │   ├── DateTimeTools.java         # 当前时间（始终真实）
│   │   │   ├── InternalDocsTools.java     # 内部知识库检索（Milvus 真实搜索）
│   │   │   ├── QueryMetricsTools.java     # Prometheus 告警（真实 API / mock）
│   │   │   ├── QueryLogsTools.java        # CLS 日志（mock 模式；真实走 MCP）
│   │   │   ├── RecallMemoryTool.java      # 召回用户记忆
│   │   │   ├── ForgetMemoryTool.java      # 删除用户记忆
│   │   │   ├── SearchKnowledgeBaseTool.java      # [Agentic] 可配置检索
│   │   │   ├── DecomposeQuestionTool.java        # [Agentic] 问题拆解
│   │   │   ├── EvaluateSearchResultsTool.java    # [Agentic] 相关性评估
│   │   │   ├── RefineQueryTool.java              # [Agentic] 查询改写
│   │   │   ├── GetSearchCapabilitiesTool.java    # [Agentic] 能力查询
│   │   │   └── ToolUtils.java             # 共享工具方法
│   │   ├── router/                        # 意图路由
│   │   │   ├── IntentRouter.java          # qwen-turbo 意图分类
│   │   │   ├── IntentCategory.java        # ALERT_DIAGNOSIS / KNOWLEDGE_RETRIEVAL / GENERAL_CHAT / UNCLEAR
│   │   │   └── IntentResult.java
│   │   ├── eval/                          # LLM-as-Judge 评估
│   │   │   ├── AIOpsEvaluator.java        # 异步 4 维度评分
│   │   │   ├── AIOpsEvalResult.java
│   │   │   ├── EvalDimension.java         # 评估维度枚举
│   │   │   ├── TestCaseLoader.java        # 加载 aiops-test-cases 用例
│   │   │   └── TestCaseMeta.java
│   │   └── mock/
│   │       └── MockDataProvider.java      # 测试 mock 数据源
│   ├── controller/
│   │   ├── v1/                            # V1 REST API（SpringDoc 注解）⭐
│   │   │   ├── ChatV1Controller.java      # /api/v1/chat、chat_stream、chat/clear、chat/session
│   │   │   ├── AIOpsV1Controller.java     # /api/v1/ai_ops（SSE）
│   │   │   ├── AuthV1Controller.java      # /api/v1/login
│   │   │   ├── UploadV1Controller.java    # /api/v1/upload、upload/reindex-failed
│   │   │   ├── MemoryV1Controller.java    # /api/v1/memory/panel、{id}、clear
│   │   │   └── HealthV1Controller.java    # /api/v1/health、milvus/health
│   │   └── legacy/                        # 旧路径 301 重定向到 v1
│   │       ├── ChatLegacyController.java  # /api/chat 系列
│   │       ├── AIOpsLegacyController.java
│   │       ├── AuthLegacyController.java
│   │       ├── UploadLegacyController.java
│   │       ├── MemoryLegacyController.java
│   │       └── MilvusLegacyController.java
│   ├── service/
│   │   ├── ChatService.java               # System Prompt 构建（历史 + 摘要 + 记忆注入）
│   │   ├── AiOpsService.java              # AIOps 轻量编排层（委托 AgentRunner）
│   │   ├── RagService.java                # 直接 RAG（线性管道）
│   │   ├── SessionManager.java            # Redis 三层会话存储 + 内存降级 ⭐
│   │   ├── SummaryGenerator.java          # 对话摘要压缩
│   │   ├── MemoryManager.java             # 记忆 CRUD（Milvus user_memory）
│   │   ├── MemoryExtractor.java           # 异步批量提取 + 冲突解决
│   │   ├── MemorySearchService.java       # 记忆向量检索
│   │   ├── MemoryDecayService.java        # 定时置信度衰减 + 过期清除
│   │   ├── VectorIndexService.java        # 文档索引（策略模式）
│   │   ├── VectorSearchService.java       # 混合检索（Dense + BM25 + RRF + Rerank）⭐
│   │   ├── VectorEmbeddingService.java    # DashScope 向量化
│   │   ├── DocumentChunkService.java      # 文档分块调度
│   │   ├── DashScopeLlmClient.java        # DashScope HTTP 客户端
│   │   ├── AgenticRagGuard.java           # Agentic RAG 护栏（轮次/阈值/超时）
│   │   ├── PromptManager.java             # Prompt 模板加载与渲染 ⭐
│   │   ├── chunk/                         # 分块策略（4 种 + 工厂）
│   │   │   ├── DocumentChunkStrategy.java
│   │   │   ├── HeadingChunkStrategy.java
│   │   │   ├── FixedSizeChunkStrategy.java
│   │   │   ├── SemanticBoundaryStrategy.java
│   │   │   ├── ParentChildStrategy.java
│   │   │   └── ChunkStrategyFactory.java
│   │   ├── rewrite/                       # 查询改写策略（4 种 + 协调器）
│   │   │   ├── QueryRewriteStrategy.java
│   │   │   ├── PromptRewriteStrategy.java
│   │   │   ├── HypotheticalAnswerStrategy.java
│   │   │   ├── DetailAbstractStrategy.java
│   │   │   ├── DirectStrategy.java
│   │   │   ├── QueryRewriteService.java   # 改写协调（Redis 缓存 + 重试）
│   │   │   └── QueryRewriteProperties.java
│   │   └── parser/                        # 文档解析（2 种）
│   │       ├── DocumentParser.java
│   │       ├── TextDocumentParser.java
│   │       ├── PdfDocumentParser.java
│   │       └── DocumentParseException.java
│   ├── security/                          # 安全模块
│   │   ├── ApiKeyAuthenticationFilter.java
│   │   ├── ApiKeyAuthenticationToken.java
│   │   ├── ApiKeyAuthManager.java
│   │   └── RateLimitInterceptor.java      # Bucket4j 令牌桶限流
│   ├── config/                            # 配置类
│   │   ├── SecurityConfig.java            # Spring Security（API Key 认证）
│   │   ├── ChatModelFactory.java          # 模型统一构建（网关/直连开关收敛点）🆕
│   │   ├── LiteLlmProperties.java         # liteLLM 网关配置属性 🆕
│   │   ├── RateLimitConfig.java           # 限流端点规则
│   │   ├── RedisConfig.java / SessionRedisProperties.java
│   │   ├── MilvusConfig.java / MilvusProperties.java
│   │   ├── ModelProperties.java           # 分层模型配置 ⭐
│   │   ├── DashScopeApiProperties.java / DashScopeConfig.java
│   │   ├── PromptProperties.java          # Prompt 语言配置
│   │   ├── MemoryProperties.java          # 记忆参数
│   │   ├── AgenticRagProperties.java      # Agentic RAG 参数
│   │   ├── ChunkStrategyProperties.java / DocumentChunkConfig.java
│   │   ├── FileUploadConfig.java          # 上传限制
│   │   ├── AsyncConfig.java               # 异步线程池（memoryExecutor）
│   │   ├── FeatureFlagStartupChecker.java # 启动时特性依赖校验
│   │   └── WebConfig.java / WebMvcConfig.java
│   ├── interceptor/
│   │   └── LogInterceptor.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   └── ...（BizException / LlmServiceException / RateLimitExceededException 等）
│   ├── dto/                               # ChatRequest / AgentEvent / AiOpsResult / ApiResponse / LoginResult ...
│   ├── client/
│   │   └── MilvusClientFactory.java
│   └── constant/
│       └── MilvusConstants.java           # biz / user_memory collection、BM25 常量
├── src/main/resources/
│   ├── application.yml                    # 主配置 ⭐
│   ├── application-dev.yml                # 开发配置（默认关闭安全/限流）
│   ├── application-prod.yml               # 生产配置
│   ├── prompts/zh/                        # Prompt 模板（agentic-rag / aiops / chat / eval / intent / memory / rewrite / summary）
│   └── static/                            # 前端 SPA
│       ├── index.html                     # IDE 风格主界面（Activity Bar + Tabs + Status Bar）
│       ├── app.js                         # SSE 解析、Markdown 渲染（marked + highlight.js）、主题循环
│       ├── styles.css                     # 3 套主题（Dark+ / One Dark Pro / SynthWave '84）
│       └── login.html / login.js / login.css  # 登录页（localStorage 持久化 API Key）
├── src/test/
│   ├── java/org/example/AIOpsQualitySmokeTest.java   # 上下文加载冒烟测试
│   └── resources/application-test.yml + test-cases/
├── aiops-test-cases/                      # AIOps 回归测试（10 场景 + mock-data/）
├── aiops-docs/                            # 运维知识库（RAG 文档源，5 篇）
├── docs/                                  # api-sse-protocol.md / feature-flags.md / 设计文档
├── vector-database.yml                    # Docker Compose（Milvus + etcd + MinIO + Attu）
├── litellm/                               # liteLLM 网关配置（config.yaml 模型注册表）🆕
├── litellm.yml                            # liteLLM Docker Compose 编排 🆕
├── .env.example                           # 环境变量模板 🆕
├── Dockerfile                             # 多阶段构建（maven → JRE）
├── Makefile                               # 一键初始化脚本
└── pom.xml
```

## 配置参考

关键配置项（完整配置见 `application.yml`）：

```yaml
server:
  port: 9900

milvus:
  host: localhost
  port: 19530

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
    mcp:
      client:
        sse:
          connections:
            tencent-cls:       # 腾讯云 CLS MCP 日志查询（cls.mock-enabled=false 时使用）
    data:
      redis:
        host: localhost
        port: 6379

# RAG 核心
rag:
  top-k: 3
  model: "qwen3-max"
  recall-count: 30              # 初始召回数（需 > rerank.threshold）
  rerank:                       # DashScope 重排序
    enabled: true
    threshold: 10
    top-k: 10
    model: "gte-rerank-v2"
  hybrid:                       # 混合召回（BM25 + 向量 + RRF）
    enabled: true
    bm25-weight: 1.0
    vector-weight: 1.0
    rrf-k: 60
  rewrite:                      # 查询改写
    strategy: direct            # prompt_rewrite | hypothetical_answer | detail_abstract | direct
    model: qwen-turbo
    cache: { enabled: true, ttl-hours: 1 }
  agentic:                      # Agentic RAG（默认关闭）
    enabled: false
    max-search-rounds: 3
    min-relevance-score: 0.6
    timeout-seconds: 60

# 文档分块
document:
  chunk:
    strategy:
      default-strategy: heading # heading | fixed-size | semantic | parent-child
      extension-overrides:
        txt: fixed-size

# 会话 Redis 存储
session:
  redis:
    ttl-hours: 24
    fallback-to-memory: true    # Redis 故障自动降级
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
    cron: "0 3 * * *"
    decay-factor: 0.1
    min-confidence: 0.3
  ttl:
    profile-hours: 2160         # 90 天
    preference-hours: 720       # 30 天

# AIOps
aiops:
  total-timeout-seconds: 300
  eval:
    enabled: true
    model: qwen-turbo
    min-pass-score: 12

# 意图路由
intent:
  router:
    enabled: true
    model: qwen-turbo
    confidence-threshold: 0.85

# 安全认证（dev 默认关闭，prod 开启）
superbiz:
  security:
    enabled: false              # API Key 认证开关
    api-key-header: X-API-Key
  rate-limit:
    enabled: false              # Bucket4j 限流开关
    endpoints:
      /api/v1/chat:        { capacity: 30, refill-rate: 5 }
      /api/v1/chat_stream: { capacity: 10, refill-rate: 2 }

# 断路器
resilience4j:
  circuitbreaker:
    instances:
      dashscope-llm: {}         # LLM 调用熔断（30s 恢复）
      litellm-llm: {}           # liteLLM 网关调用熔断 🆕
      dashscope-embedding: {}
      milvus-search: {}
  ratelimiter:
    instances:
      file-upload: { limit-for-period: 10, limit-refresh-period: 1m }

# liteLLM 大模型网关（可选）🆕
litellm:
  enabled: false                     # true=走网关，false=DashScope 直连
  base-url: http://localhost:4000
  api-key: ${LITELLM_API_KEY:sk-litellm-change-me}

# 分层模型
ai:
  model:
    chat:       { name: qwen3-max, temperature: 0.7, max-token: 2000 }
    aiops:      { supervisor: {...}, planner: {...}, executor: {...} }
    lightweight:{ name: qwen-turbo }
    reasoning:  { name: qwen3-max }
    rewrite:    { name: qwen-turbo, max-token: 500 }

# SpringDoc OpenAPI
springdoc:
  api-docs:
    path: /api/v1/docs/json
  swagger-ui:
    path: /api/v1/docs/ui
```

## 特性开关

| 开关 | 默认 | 说明 |
|------|------|------|
| `memory.enabled` | true | 长期记忆系统 |
| `rag.agentic.enabled` | false | Agentic RAG 多轮搜索 |
| `rag.hybrid.enabled` | true | BM25 + 向量双路召回 |
| `rag.rerank.enabled` | true | DashScope Rerank 重排序 |
| `rag.rewrite.cache.enabled` | true | 查询改写结果缓存 |
| `cls.mock-enabled` | false | CLS 日志模拟 vs MCP 真实 |
| `prometheus.mock-enabled` | false | Prometheus 模拟 vs 真实 API |
| `superbiz.security.enabled` | false(dev)/true(prod) | API Key 认证 |
| `superbiz.rate-limit.enabled` | false(dev)/true(prod) | 请求限流 |
| `intent.router.enabled` | true | 意图识别路由 |
| `session.redis.summary.enabled` | true | 对话摘要生成 |
| `aiops.eval.enabled` | true | LLM-as-Judge 质量评估 |
| `litellm.enabled` | false | liteLLM 大模型网关（true=经网关，false=DashScope 直连） |

> 完整说明见 `docs/feature-flags.md`；启动时 `FeatureFlagStartupChecker` 会校验开关依赖（如 memory 需要 Redis）。

## SSE 流式协议

`POST /api/v1/chat_stream`（`Accept: text/event-stream`）事件：

| event | 说明 |
|-------|------|
| `message` | 通用消息容器（`AgentEvent` JSON） |
| `content` | 文本增量 `{"type":"CONTENT_CHUNK","data":"..."}` |
| `tool_start` / `tool_end` | 工具调用开始 / 结束 |
| `error` | 错误事件 |
| `done` | 流完成（携带 `sessionId`） |

完整协议见 `docs/api-sse-protocol.md`。

## 内部知识库

`aiops-docs/` 目录包含 5 篇运维知识文档（`make upload` 时自动向量化到 Milvus）：

- `cpu_high_usage.md` — CPU 高负载处理
- `memory_high_usage.md` — 内存高负载处理
- `disk_high_usage.md` — 磁盘高负载处理
- `service_unavailable.md` — 服务不可用处理
- `slow_response.md` — 慢响应处理

## AIOps 回归测试用例集

`aiops-test-cases/` 提供 10 个运维场景的测试定义（Markdown + YAML frontmatter，含预期根因），每个场景配套 `mock-data/*.json`：

`cpu_high_usage` / `memory_high_usage` / `disk_high_usage` / `service_unavailable` / `slow_response` / `db_connection_pool_full` / `mq_consumer_lag` / `k8s_pod_crashloop` / `ssl_cert_expiring` / `api_timeout_cascade`

## 测试

```bash
cd SuperBizAgent-release-2026-05-17
mvn test           # AIOpsQualitySmokeTest：上下文加载冒烟测试
mvn clean compile  # 编译验证
```

## 开发特性

- **策略模式**：文档解析（2 种）、分块（4 种）、查询改写（4 种）均通过配置切换
- **降级保护**：LLM 调用含断路器熔断 + 超时重试；Redis 故障降级内存；BM25 路失败不影响 Dense 路；Rerank 失败返回原始排序
- **条件注册**：记忆工具（`memory.enabled`）、Agentic RAG 工具（`rag.agentic.enabled`）按配置条件注册；CLS 日志工具 `@Autowired(required=false)` 按需加载
- **API 兼容**：旧路径 301 重定向至 `/api/v1`，客户端无需改造
- **异步记忆提取**：`@Async("memoryExecutor")` 不阻塞对话
- **定时维护**：`@Scheduled` 记忆衰减与过期清理
- **会话隔离**：ReentrantLock 会话锁 + Redis 持久化 + 内存降级
- **Prompt 模板化**：全部 Prompt 收敛到 `prompts/` 目录，支持 frontmatter 元数据与 Mustache 渲染
