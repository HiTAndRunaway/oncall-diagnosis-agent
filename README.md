# SuperBizAgent

基于 Spring Boot 3.2 + Spring AI Alibaba Agent Framework 的企业级智能运维助手，集成了 RAG 知识库检索与多 Agent 协作编排能力。

## 核心能力

| 能力 | 描述 |
|------|------|
| **智能对话** | 支持普通/流式双模式，带工具调用（时间、知识库、Prometheus 告警等） |
| **Agentic RAG** | Agent 自主编排检索策略：问题拆解 → 多轮检索 → 相关性评估 → 改写重试 |
| **AIOps 告警分析** | SupervisorAgent 编排 Planner-Executor-Replanner 循环，自动生成告警分析报告 |
| **文件上传** | 支持 PDF / TXT / Markdown 上传并自动向量化入库 |
| **混合检索** | Dense Vector + BM25 双路并行召回 + RRF 融合 + DashScope Rerank |
| **会话管理** | Redis 持久化会话历史，支持摘要压缩 + 滑动窗口 |

## 技术栈

```
Spring Boot 3.2  |  Spring AI Alibaba Agent Framework  |  DashScope (LLM + Embedding)
Milvus 2.5       |  Redis 7                             |  SSE 流式输出
PDFBox 3.0       |  Gson / Jackson                      |  Lombok
```

## 快速开始

### 1. 环境准备

- JDK 17+
- Maven 3.9+
- Docker（推荐，用于 Milvus + Redis）

### 2. 设置 API Key

```bash
export DASHSCOPE_API_KEY=your-dashscope-api-key
```

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
mvn clean install
mvn spring-boot:run
```

应用运行在 **http://localhost:9900**。

### 5. 健康检查

```bash
curl http://localhost:9900/milvus/health
```

## API 端点

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/chat` | POST | 普通对话（含工具调用、对话历史） |
| `/api/chat_stream` | POST | SSE 流式对话（实时输出） |
| `/api/ai_ops` | POST | AIOps 告警分析（多 Agent 协作） |
| `/api/chat/clear` | POST | 清空会话历史 |
| `/api/chat/session/{id}` | GET | 查询会话信息 |
| `/api/upload` | POST | 上传文件（自动向量化） |
| `/milvus/health` | GET | Milvus 健康检查 |

## 项目结构

```
src/main/java/org/example/
├── Main.java                              # Spring Boot 入口
├── agent/tool/                            # Agent 工具集
│   ├── InternalDocsTools.java             # 内部知识库检索
│   ├── SearchKnowledgeBaseTool.java       # [Agentic] 可配置检索
│   ├── EvaluateSearchResultsTool.java     # [Agentic] 相关性评估
│   ├── RefineQueryTool.java              # [Agentic] 查询改写
│   ├── DecomposeQuestionTool.java        # [Agentic] 问题拆解
│   ├── GetSearchCapabilitiesTool.java    # [Agentic] 能力查询
│   ├── DateTimeTools.java                # 日期时间
│   ├── QueryMetricsTools.java            # Prometheus 告警
│   ├── QueryLogsTools.java               # CLS 日志（Mock）
│   └── ToolUtils.java                    # 共享工具方法
├── config/                                # 配置类
│   ├── AgenticRagProperties.java          # Agentic RAG 护栏配置
│   └── ...（Milvus/Redis/分块/文件上传等配置）
├── controller/
│   ├── ChatController.java               # 对话 + AIOps API
│   ├── FileUploadController.java         # 文件上传 API
│   └── MilvusCheckController.java        # 健康检查
├── service/
│   ├── ChatService.java                   # ReactAgent 工厂 + 系统提示词
│   ├── AiOpsService.java                  # AIOps 多 Agent 编排
│   ├── RagService.java                    # 直接 RAG（线性管道）
│   ├── VectorSearchService.java          # 混合检索（Dense + BM25 + RRF + Rerank）
│   ├── VectorIndexService.java           # 文档索引（策略模式）
│   ├── VectorEmbeddingService.java       # DashScope 向量化
│   ├── DocumentChunkService.java         # Markdown 标题分块
│   ├── SessionManager.java               # Redis 会话管理
│   ├── SummaryGenerator.java             # 对话摘要压缩
│   ├── AgenticRagGuard.java              # Agentic RAG 护栏
│   ├── DashScopeLlmClient.java           # DashScope HTTP 客户端
│   ├── chunk/                             # 文档切分策略（5 种）
│   │   ├── DocumentChunkStrategy.java     # 策略接口
│   │   ├── HeadingChunkStrategy.java      # 标题拆分
│   │   ├── FixedSizeChunkStrategy.java    # 固定大小
│   │   ├── SemanticBoundaryStrategy.java  # 语义边界
│   │   ├── ParentChildStrategy.java       # small-to-big
│   │   └── ChunkStrategyFactory.java      # 策略工厂
│   ├── parser/                            # 文档解析策略（3 种）
│   │   ├── DocumentParser.java            # 解析接口
│   │   ├── TextDocumentParser.java        # TXT/MD
│   │   ├── PdfDocumentParser.java         # PDF
│   │   └── DocumentParseException.java
│   └── rewrite/                           # 查询改写策略（4 种）
│       ├── QueryRewriteStrategy.java      # 策略接口
│       ├── PromptRewriteStrategy.java     # LLM 改写
│       ├── HypotheticalAnswerStrategy.java # 假设答案
│       ├── DetailAbstractStrategy.java    # 细节抽象
│       ├── DirectStrategy.java            # 直通
│       ├── QueryRewriteService.java       # 改写协调（含缓存+重试）
│       └── QueryRewriteProperties.java
└── dto/                                   # 数据传输对象
```

## RAG 架构

```
文件上传                             用户查询
    │                                    │
    ▼                                    ▼
DocumentParser ──策略──► 文件解析    QueryRewriteService ──策略──► 查询改写
    │                                    │
    ▼                                    ▼
ChunkStrategyFactory ──策略──► 切分  VectorSearchService ──混合检索──►
    │                              ┌─ Dense Vector (L2)
    ▼                              ├─ BM25 Sparse (IP)
VectorEmbedding ──► Milvus         ├─ RRF 融合
                                   └─ DashScope Rerank
                                        │
                                        ▼
                                   Top-K 结果 → Agent/LLM 生成答案
```

### Agentic RAG 模式（`rag.agentic.enabled: true`）

Agent 在 ReAct 循环中自动编排检索流程：

```
用户问题
  → decomposeQuestion ──拆解──► [子问题 1, 子问题 2, ...]
  → searchKnowledgeBase ──检索──► 结果 + _meta 轮次信息
  → evaluateSearchResults ──评估──► PROCEED / REFINE
  → refineQuery ──改写──► 新查询 → 重新检索
  → 综合生成答案
```

**护栏**：最大 3 轮检索 / 相关性阈值 0.6 / 60s 超时 / 自动降级

关闭开关后完全回退到传统 RAG，不影响现有行为。

## AIOps 多 Agent 系统

```
SupervisorAgent
  ├─ PlannerAgent    (分解告警 → 规划步骤 → 输出报告模板)
  └─ ExecutorAgent   (执行工具调用 → 返回结构化反馈)

持续循环直到 Planner 输出 decision=FINISH → 生成完整的《告警分析报告》
```

报告包含：活跃告警清单 → 根因分析 → 处理方案执行 → 结论与建议

## 配置参考

关键配置项（完整配置见 `application.yml`）：

```yaml
# RAG 核心
rag:
  top-k: 3
  hybrid.enabled: true          # 双路召回
  rerank.enabled: true          # 重排序
  rewrite.strategy: direct      # 查询改写策略

# Agentic RAG（默认关闭）
rag.agentic:
  enabled: false
  max-search-rounds: 3
  min-relevance-score: 0.6

# 文档分块
document.chunk.strategy:
  default-strategy: heading
  extension-overrides:
    txt: fixed-size
```

## 内部知识库

`aiops-docs/` 目录包含 5 篇运维知识文档：

- `cpu_high_usage.md` — CPU 高负载处理
- `memory_high_usage.md` — 内存高负载处理
- `disk_high_usage.md` — 磁盘高负载处理
- `service_unavailable.md` — 服务不可用处理
- `slow_response.md` — 慢响应处理

这些文档在 `make upload`（或首次运行时通过 API 上传）时自动向量化到 Milvus。

## 开发特性

- **策略模式**：文档解析（3 种）、文档切分（5 种）、查询改写（4 种）、Agentic 工具（5 种）均采用策略模式，通过配置即可切换
- **降级保护**：所有 LLM 调用含超时重试 + 自动降级，BM25 路失败不影响 Dense 路
- **条件注册**：Agentic RAG 工具通过 `@ConditionalOnProperty` 条件注册，`Mock` 工具通过 `@Autowired(required=false)` 按需加载
- **会话隔离**：`ThreadLocal` 护栏计数器 + `ReentrantLock` 会话锁 + Redis 持久化
