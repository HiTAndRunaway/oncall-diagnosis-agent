# LiteLLM 集成方案（修订版）

## Context

SuperBizAgent 当前通过 4 条路径直连 DashScope，完全没有 Token 计数和预算管控。对于包含 Agent 循环的 AIOps 场景，Token 消耗不可预测，存在隐性成本风险。

**目标**：集成 LiteLLM 作为 LLM 网关，对核心 Chat/AIOps 路径提供 Token 计数、预算控制和用量可视化。

## 关键发现

**DashScope 已经提供了 OpenAI 兼容端点：** `https://dashscope.aliyuncs.com/compatible-mode/v1`

这意味着 LiteLLM 可以直接使用 `openai/` 模型前缀代理 DashScope，无需协议翻译。DashScope 兼容端点已支持 `qwen-max`、`qwen-plus`、`qwen-turbo-latest` 等模型名。这大大简化了集成复杂度。

## 集成架构

```
Spring Boot App (port 9900)
    │
    ├── Chat / ChatStream / AIOps ──→ LiteLLM Proxy (port 4000) ──→ DashScope 兼容端点
    │    (OpenAiChatModel)              │                            (/compatible-mode/v1)
    │                                   ├── PostgreSQL (spend logs)
    │                                   ├── Token 计数 + 预算控制
    │                                   └── Admin UI (port 4000/ui)
    │
    ├── Embedding ──→ DashScope SDK ──→ DashScope API (直连)
    └── Rerank    ──→ REST ──→ DashScope API (直连)
```

**仅 Chat/AIOps 路径走网关。** Embedding 和 Rerank 保持直连。

## 实施步骤

### 步骤 1：Docker 基础设施

**文件**: `litellm-config.yaml` (新增)

```yaml
general_settings:
  master_key: ${LITELLM_MASTER_KEY}
  database_url: postgresql://llmproxy:dbpassword99@litellm-db:5432/litellm

litellm_settings:
  drop_params: true
  set_verbose: true

model_list:
  - model_name: qwen3-max
    litellm_params:
      model: openai/qwen-max                    # DashScope 兼容端点
      api_base: https://dashscope.aliyuncs.com/compatible-mode/v1
      api_key: ${DASHSCOPE_API_KEY}
      rpm: 60
  - model_name: qwen-turbo
    litellm_params:
      model: openai/qwen-turbo-latest
      api_base: https://dashscope.aliyuncs.com/compatible-mode/v1
      api_key: ${DASHSCOPE_API_KEY}
      rpm: 120
  - model_name: qwen-plus
    litellm_params:
      model: openai/qwen-plus
      api_base: https://dashscope.aliyuncs.com/compatible-mode/v1
      api_key: ${DASHSCOPE_API_KEY}
      rpm: 60

router_settings:
  num_retries: 2
  allowed_fails: 3
  cooldown_time: 30
```

**文件**: `vector-database.yml` (修改 — 新增两个 service)

```yaml
  litellm:
    container_name: super-biz-litellm
    image: ghcr.io/berriai/litellm:main-v1.60.0-stable
    ports:
      - "4000:4000"
    volumes:
      - ./litellm-config.yaml:/app/config.yaml
    environment:
      - LITELLM_MASTER_KEY=${LITELLM_MASTER_KEY:-sk-litellm-master-key}
      - DASHSCOPE_API_KEY=${DASHSCOPE_API_KEY}
    command: ["--config", "/app/config.yaml", "--port", "4000"]
    depends_on:
      litellm-db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4000/health"]
      interval: 15s
      timeout: 5s
      retries: 5

  litellm-db:
    container_name: super-biz-litellm-db
    image: postgres:16-alpine
    environment:
      - POSTGRES_DB=litellm
      - POSTGRES_USER=llmproxy
      - POSTGRES_PASSWORD=dbpassword99
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/litellm-db:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U llmproxy -d litellm"]
      interval: 10s
      timeout: 5s
      retries: 5
```

> PostgreSQL 是 LiteLLM 的 spend tracking / UI 的必需依赖。

### 步骤 2：Maven 依赖

**文件**: `pom.xml` (新增依赖)

```xml
<!-- Spring AI OpenAI Starter（通过 LiteLLM 调用 DashScope 兼容端点） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

版本由 `${spring-ai.version}`（1.1.0）BOM 统一管理。现有的 `spring-ai-alibaba-starter-dashscope` 保留不动（Embedding 和 Rerank 仍需使用）。

### 步骤 3：新增 YAML 配置

**文件**: `application.yml` (追加)

```yaml
# LiteLLM 网关配置
litellm:
  enabled: false                    # 总开关：true=走网关，false=直连 DashScope
  base-url: http://localhost:4000
  api-key: ${LITELLM_MASTER_KEY:sk-litellm-master-key}
  timeout: 180000
  budget:
    enabled: false                  # 应用层 Token 预算开关
    daily-limit: 100000             # 每日限额（token）
    monthly-limit: 2000000          # 每月限额（token）
  token-tracking:
    enabled: true                   # 是否将用量写入 Redis + 结构化日志
    redis-ttl-days: 90
```

### 步骤 4：新增配置类

**文件**: `src/main/java/org/example/config/LitellmProperties.java` (新增)

`@ConfigurationProperties(prefix = "litellm")` — 绑定上述 yml 配置。

### 步骤 5：新增核心工厂 — `LlmGatewayService`

**文件**: `src/main/java/org/example/service/LlmGatewayService.java` (新增)

这是最关键的类，集中化管理 ChatModel 的创建，根据 `litellm.enabled` 返回 `OpenAiChatModel`（走网关）或 `DashScopeChatModel`（直连）：

```java
@Service
public class LlmGatewayService {

    public ChatModel createChatModel(String modelName, double temp, int maxTokens, double topP) {
        if (litellmProperties.isEnabled()) {
            return createOpenAiChatModel(modelName, temp, maxTokens, topP);
        } else {
            return createDashScopeChatModel(modelName, temp, maxTokens, topP);
        }
    }

    // 内部方法：
    // createOpenAiChatModel() → OpenAiApi → OpenAiChatModel → LiteLLM (port 4000)
    // createDashScopeChatModel() → DashScopeApi → DashScopeChatModel → DashScope (直连)
}
```

**优势**：相比在每个 Controller 里写 if/else，工厂模式集中控制、易测试、易维护。

### 步骤 6：新增 Token 追踪

**文件**: `src/main/java/org/example/service/TokenUsageTracker.java` (新增)

- Redis 记录每日/每月 Token 消耗（`litellm:tokens:daily:2026-07-17`）
- `tryConsume(estimatedTokens)` → 预算超限时抛出 `TokenBudgetExceededException`
- 结构化日志输出到 `TOKEN_USAGE` logger

**文件**: `src/main/java/org/example/controller/TokenUsageController.java` (新增)

- `GET /api/admin/token-usage` → 返回当日/当月用量

> **重要限制**：ReactAgent（Spring AI Alibaba Agent Framework）的内部多轮 LLM 调用不暴露 Token 用量。对 `/chat`、`/chat_stream`、`/ai_ops` 路径，Token 统计依赖 LiteLLM 服务端记录。应用层的 `TokenUsageTracker` 主要用于直接 ChatModel.call() 路径（SummaryGenerator、QueryRewrite）。

### 步骤 7：修改 ChatService

**文件**: `src/main/java/org/example/service/ChatService.java` (修改)

改动：
1. 注入 `LlmGatewayService`
2. `createReactAgent` 参数类型从 `DashScopeChatModel` 改为 `ChatModel` 接口 → 兼容两种模型
3. `createStandardChatModel()` 委托给 `llmGatewayService.createStandardChatModel()`

### 步骤 8：修改 ChatController

**文件**: `src/main/java/org/example/controller/ChatController.java` (修改)

改动：
1. `/chat` 端点：`ChatModel chatModel = chatService.createStandardChatModel();`（模型类型由配置决定）
2. `/chat_stream` 端点：同上
3. `/ai_ops` 端点：`ChatModel chatModel = chatService.createChatModel("qwen-plus", 0.3, 8000, 0.9);`

### 步骤 9：修改其他 LLM 调用方

| 文件 | 改动 |
|------|------|
| `AiOpsService.java` | 方法签名 `DashScopeChatModel` → `ChatModel` |
| `SummaryGenerator.java` | ChatModel 创建改为调用 `LlmGatewayService` |
| `QueryRewriteService.java` | 同上，移除直接创建 DashScopeApi 的代码 |

### 步骤 10：更新 Makefile + CLAUDE.md

- Makefile: `make up` 等待 LiteLLM 健康检查；新增 `make litellm-logs` / `make litellm-ui`
- CLAUDE.md: 补充 LiteLLM 配置说明

## 实施顺序

```
Phase 1 (基础): 步骤 1-3 → 部署 Docker + 配置文件
Phase 2 (核心): 步骤 4-8 → 新增类 + 改造 Chat 路径
Phase 3 (覆盖): 步骤 9   → 改造其余 LLM 调用方
Phase 4 (收尾): 步骤 10  → Makefile + 文档
```

## 风险与注意点

| 风险 | 缓解 |
|------|------|
| ReactAgent 可能不兼容 OpenAiChatModel | POC 先行：手动创建 OpenAiChatModel → 构建 ReactAgent → 测试 tool-calling |
| DashScope 兼容端点的 function calling 格式差异 | LiteLLM 的 `drop_params: true` 自动丢弃不兼容参数 |
| LiteLLM 不可用时整个 Chat 路径不可用 | `LlmGatewayService` 增加 try-catch，连接失败时自动 fallback 到直连 DashScope |
| 流式 SSE 的 Token 计数 | 请求前预估 + 响应后校正；ReactAgent 路径依赖 LiteLLM 服务端记录 |
| 额外一跳延迟 | 同 Docker 网络内 ~5-20ms，可接受 |

## 验证方案

1. `mvn clean compile` — 编译通过
2. `litellm.enabled=false` — 行为与改动前完全一致（回退验证）
3. `litellm.enabled=true` — `/chat`、`/chat_stream`、`/ai_ops` 均正常工作
4. `curl http://localhost:4000/health` — LiteLLM 健康检查通过
5. LiteLLM UI (`localhost:4000/ui`) — 可见 spend logs
6. `GET /api/admin/token-usage` — 返回正确的用量数据
7. 超过 daily-limit — 返回 429
