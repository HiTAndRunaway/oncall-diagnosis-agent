# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与运行

```bash
# 设置必需的环境变量
export DASHSCOPE_API_KEY=your-api-key

# 构建
mvn clean install

# 运行
mvn spring-boot:run

# 一键初始化（启动 Docker Milvus + 应用 + 上传文档）
make init

# 仅启动 Docker（Milvus + etcd + MinIO + Attu 管理界面，端口 :8000）
make up

# 仅启动应用（后台运行，日志输出到 server.log）
make start

# 停止所有服务
make stop && make down
```

应用运行在 **端口 9900**。健康检查：`GET /milvus/health`。

## 架构

这是一个基于 **Spring Boot 3.2 + Spring AI Alibaba Agent Framework** 的应用，包含两个核心子系统：

### 1. RAG（检索增强生成）
**流水线**：文件上传 → 文档分块 → DashScope text-embedding-v4 → Milvus 向量数据库 → 相似度搜索 → DashScope LLM 生成答案。

关键类流程：
- `FileUploadController` → `VectorIndexService.indexSingleFile()` → `DocumentChunkService`（按 Markdown 标题分割，最大 800 字符，100 字符重叠） → `VectorEmbeddingService`（DashScope API） → Milvus 插入
- 查询：`InternalDocsTools.queryInternalDocs()` → `VectorSearchService.searchSimilarDocuments()`（使用 L2 距离度量） → 将 Top-K 结果以 JSON 形式返回给 Agent
- `RagService` 也可单独用于流式 RAG 回答（默认使用 `qwen3-max` 模型）

### 2. AIOps 多智能体系统
使用 Spring AI Alibaba 的 **SupervisorAgent** 来编排 **Planner-Executor-Replanner** 循环：

- **SupervisorAgent**（`ai_ops_supervisor`）：顶层调度器，决定是调用 planner_agent 还是 executor_agent
- **Planner Agent**（`planner_agent`）：分解告警，输出 `{decision: PLAN|EXECUTE|FINISH, step, ...}`。当输出 FINISH 时，按照严格的模板输出完整的 Markdown 告警分析报告（活跃告警表 → 根因分析 → 修复步骤 → 结论）
- **Executor Agent**（`executor_agent`）：执行 Planner 计划中的第一个步骤，调用工具，返回结构化的 JSON 反馈（`{status, summary, evidence, nextHint}`）

循环持续进行，直到 Planner 输出 `decision=FINISH`。入口：`POST /api/ai_ops` → `AiOpsService.executeAiOpsAnalysis()`。

### Agent 工具 (`agent/tool/`)

所有工具均为带有 `@Tool` 注解的 `@Component`，作为 `ReactAgent` 上的方法工具注册：

| 工具 | 方法 | 真实/模拟 |
|------|------|----------|
| `DateTimeTools` | `getCurrentDateTime()` | 始终真实 |
| `InternalDocsTools` | `queryInternalDocs(query)` | 真实（Milvus 搜索） |
| `QueryMetricsTools` | `queryPrometheusAlerts()` | 真实（Prometheus API）或模拟（`prometheus.mock-enabled`） |
| `QueryLogsTools` | `queryLogs(region, logTopic, query, limit)`, `getAvailableLogTopics()` | 仅模拟模式（`cls.mock-enabled`）；真实模式期望通过 MCP 注入工具 |

当 `cls.mock-enabled=false` 时，`QueryLogsTools` 不作为 bean 注册 —— 真实的 CLS 日志查询能力来自通过 `spring.ai.mcp.client.sse.connections.tencent-cls` 注入的 MCP 工具。

### 聊天服务
`ChatService` 是创建 `ReactAgent` 实例的共享工厂。它：
1. 创建 `DashScopeApi` + `DashScopeChatModel`（温度 0.7，maxToken 2000，topP 0.9）
2. 构建包含对话历史的系统提示词（滑动窗口，最多 6 对消息）
3. 根据 `QueryLogsTools` 是否可用（`@Autowired(required = false)`）动态构建方法工具数组
4. 通过 `ToolCallbackProvider` 合并 MCP 提供的工具

### 会话管理
`ChatController` 维护一个 `ConcurrentHashMap<String, SessionInfo>`，每个会话配有 `ReentrantLock`。历史记录为一个 `{role, content}` Map 列表，上限为 6 对（12 条记录），超出时自动淘汰最早的消息对。

## 配置

- `application.yml` — 主配置文件（服务器端口 9900，Milvus 主机:端口，从环境变量读取 DashScope API 密钥，Prometheus URL，CLS 模拟开关，RAG top-K/模型，文档分块大小）
- `vector-database.yml` — 用于 Milvus 独立部署 + etcd + MinIO + Attu 管理界面的 Docker Compose 文件
- 到腾讯 CLS 的 MCP SSE 连接配置在 `spring.ai.mcp.client.sse.connections.tencent-cls`

## 控制器

- `ChatController` (`/api`)：`/chat`、`/chat_stream`、`/ai_ops`、`/chat/clear`、`/chat/session/{id}` — 所有聊天/AIOps 端点，支持 SSE 流式传输
- `FileUploadController` (`/api/upload`)：文件上传并自动向量化
- `MilvusCheckController` (`/milvus/health`)：Milvus 连接性检查

## 前端

单页应用，位于 `src/main/resources/static/`：
- `index.html` — Gemini 风格的界面，包含侧边栏、聊天区域、模式选择器（快速 vs 流式）
- `app.js` — `SuperBizAgentApp` 类：SSE 解析、Markdown 渲染（marked.js + highlight.js）、localStorage 聊天历史、带遮罩层的文件上传
- `styles.css` — 受 Google Material 启发的设计

## aiops-docs/

五个 Markdown 文件，作为告警处理流程的 RAG 知识库：`cpu_high_usage.md`、`memory_high_usage.md`、`disk_high_usage.md`、`service_unavailable.md`、`slow_response.md`。这些文件在 `make upload` 时被向量化，在 AIOps 分析过程中由 `InternalDocsTools` 进行搜索。

## 关键依赖

- `spring-ai-alibaba-starter-dashscope` — DashScope 聊天/嵌入
- `spring-ai-alibaba-agent-framework` — ReactAgent、SupervisorAgent、多智能体编排
- `milvus-sdk-java` 2.6.10 — Milvus 向量数据库客户端
- `dashscope-sdk-java` 2.17.0 — 阿里云 DashScope（文本嵌入）
- `spring-ai-starter-mcp-client-webflux` — 用于腾讯 CLS 集成的 MCP 客户端

## 注意事项

1.每次用户要求输出方案之后，将方案保存到当前项目下的/session/idea文件夹中（如果没有该路径就创建路径）.

2.代码实现前，先从master分支拉一个新的feature分支出来，在新分支上实现。每次代码实现完之后，需要进行测试，保证能够编译成功，运行无误。

3.测试完后，使用code review expert skill进行代码审查。

4.代码审查完后需要解决审查出来的问题，审查完后再次测试，没问题后提交并推送到远程分支（失败可以重试，重试5次，如果不成功就停止）

## Agent skills

### Issue tracker

Issues live as GitHub issues on `HiTAndRunaway/oncall-diagnosis-agent`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five canonical triage labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

