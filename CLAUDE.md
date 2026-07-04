# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Set required environment variable
export DASHSCOPE_API_KEY=your-api-key

# Build
mvn clean install

# Run
mvn spring-boot:run

# One-click init (starts Docker Milvus + app + uploads docs)
make init

# Docker only (Milvus + etcd + MinIO + Attu GUI at :8000)
make up

# App only (background, logs to server.log)
make start

# Stop all
make stop && make down
```

The app serves on **port 9900**. Health check: `GET /milvus/health`.

## Architecture

This is a **Spring Boot 3.2 + Spring AI Alibaba Agent Framework** application with two core subsystems:

### 1. RAG (Retrieval-Augmented Generation)
**Pipeline**: file upload → document chunking → DashScope text-embedding-v4 → Milvus vector DB → similarity search → DashScope LLM answer generation.

Key classes flow:
- `FileUploadController` → `VectorIndexService.indexSingleFile()` → `DocumentChunkService` (splits by Markdown headings, max 800 chars, 100 overlap) → `VectorEmbeddingService` (DashScope API) → Milvus insert
- Query: `InternalDocsTools.queryInternalDocs()` → `VectorSearchService.searchSimilarDocuments()` (L2 metric) → returns top-K results as JSON to the Agent
- `RagService` can also be used standalone for streaming RAG answers (uses `qwen3-max` model by default)

### 2. AIOps Multi-Agent System
Uses Spring AI Alibaba's **SupervisorAgent** to orchestrate a **Planner-Executor-Replanner** loop:

- **SupervisorAgent** (`ai_ops_supervisor`): top-level dispatcher, decides whether to call planner_agent or executor_agent
- **Planner Agent** (`planner_agent`): decomposes alerts, outputs `{decision: PLAN|EXECUTE|FINISH, step, ...}`. When FINISH, outputs a complete Markdown alert analysis report following a strict template (active alerts table → root cause analysis → remediation steps → conclusion)
- **Executor Agent** (`executor_agent`): executes the first step from Planner's plan, calls tools, returns structured JSON feedback (`{status, summary, evidence, nextHint}`)

The loop runs until Planner outputs `decision=FINISH`. Entry point: `POST /api/ai_ops` → `AiOpsService.executeAiOpsAnalysis()`.

### Agent Tools (`agent/tool/`)

All tools are `@Component` with `@Tool` annotations, registered as method tools on `ReactAgent`:

| Tool | Method | Real/Mock |
|------|--------|-----------|
| `DateTimeTools` | `getCurrentDateTime()` | Always real |
| `InternalDocsTools` | `queryInternalDocs(query)` | Real (Milvus search) |
| `QueryMetricsTools` | `queryPrometheusAlerts()` | Real (Prometheus API) or Mock (`prometheus.mock-enabled`) |
| `QueryLogsTools` | `queryLogs(region, logTopic, query, limit)`, `getAvailableLogTopics()` | Mock only (`cls.mock-enabled`); real mode expects MCP-injected tools |

When `cls.mock-enabled=false`, `QueryLogsTools` is not registered as a bean — the real CLS log querying comes from MCP tools injected via `spring.ai.mcp.client.sse.connections.tencent-cls`.

### Chat Service
`ChatService` is the shared factory for creating `ReactAgent` instances. It:
1. Creates `DashScopeApi` + `DashScopeChatModel` (temperature 0.7, maxToken 2000, topP 0.9)
2. Builds system prompt with conversation history (sliding window of max 6 message pairs)
3. Dynamically builds the method tools array based on whether `QueryLogsTools` is available (`@Autowired(required = false)`)
4. Merges MCP-provided tools via `ToolCallbackProvider`

### Session Management
`ChatController` maintains `ConcurrentHashMap<String, SessionInfo>` with `ReentrantLock` per session. History is a list of `{role, content}` maps, capped at 6 pairs (12 entries), with automatic oldest-pair eviction.

## Configuration

- `application.yml` — main config (server port 9900, Milvus host:port, DashScope API key from env var, Prometheus URL, CLS mock toggle, RAG top-K/model, document chunk sizes)
- `vector-database.yml` — Docker Compose for Milvus standalone + etcd + MinIO + Attu GUI
- MCP SSE connection to Tencent CLS configured in `spring.ai.mcp.client.sse.connections.tencent-cls`

## Controllers

- `ChatController` (`/api`): `/chat`, `/chat_stream`, `/ai_ops`, `/chat/clear`, `/chat/session/{id}` — all chat/AIOps endpoints with SSE streaming support
- `FileUploadController` (`/api/upload`): file upload with auto-vectorization
- `MilvusCheckController` (`/milvus/health`): Milvus connectivity check

## Frontend

Single-page app in `src/main/resources/static/`:
- `index.html` — Gemini-style UI with sidebar, chat area, mode selector (quick vs stream)
- `app.js` — `SuperBizAgentApp` class: SSE parsing, Markdown rendering (marked.js + highlight.js), localStorage chat history, file upload with overlay
- `styles.css` — Google Material-inspired design

## aiops-docs/

Five Markdown files serving as the RAG knowledge base for alert handling procedures: `cpu_high_usage.md`, `memory_high_usage.md`, `disk_high_usage.md`, `service_unavailable.md`, `slow_response.md`. These are vectorized on `make upload` and searched by `InternalDocsTools` during AIOps analysis.

## Key Dependencies

- `spring-ai-alibaba-starter-dashscope` — DashScope chat/embedding
- `spring-ai-alibaba-agent-framework` — ReactAgent, SupervisorAgent, multi-agent orchestration
- `milvus-sdk-java` 2.6.10 — Milvus vector DB client
- `dashscope-sdk-java` 2.17.0 — Alibaba Cloud DashScope (text embedding)
- `spring-ai-starter-mcp-client-webflux` — MCP client for Tencent CLS integration
