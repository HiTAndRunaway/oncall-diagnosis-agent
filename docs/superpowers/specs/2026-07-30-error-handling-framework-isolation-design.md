# SuperBizAgent 错误处理一致性 & 框架耦合隔离 — 技术设计文档

> 版本：v1.0 | 日期：2026-07-30 | 基于改进计划 [P1-13, P1-16](../../session/idea/2026-07-22-superbizagent-improvement-plan.md)

---

## 目录

1. [架构概述](#1-架构概述)
2. [核心接口层设计](#2-核心接口层设计)
3. [实现层设计](#3-实现层设计)
4. [异常体系设计](#4-异常体系设计)
5. [全局异常处理器](#5-全局异常处理器)
6. [异步异常处理](#6-异步异常处理)
7. [Controller 层迁移指南](#7-controller-层迁移指南)
8. [流式抽象设计](#8-流式抽象设计)
9. [配置设计](#9-配置设计)
10. [文件清单](#10-文件清单)
11. [测试策略](#11-测试策略)

---

## 1. 架构概述

### 1.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Controller 层                             │
│  ChatController / MemoryController / FileUploadController    │
│  / AuthController / MilvusCheckController                    │
│                                                              │
│  依赖: AgentRunner (接口)  |  Flux<AgentEvent> (DTO)         │
│  不再直接引用: ReactAgent, DashScopeChatModel, OverAllState  │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                    Service 层                                  │
│  ChatService → 依赖 AgentRunner, LlmProvider                  │
│  AiOpsService → 依赖 AgentRunner (SupervisorAgent 封装在内)   │
│  RagService / SummaryGenerator / ...                         │
│                                                              │
│  框架具体类仅存在于此层的实现类内部                             │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                   核心接口层 (新增)                             │
│                                                              │
│  AgentRunner.java        — Agent 执行抽象                     │
│  LlmProvider.java        — LLM 调用抽象                       │
│  AgentEvent.java         — 流式事件 DTO                       │
│  AiOpsResult.java        — 替代 OverAllState 的结果 DTO       │
│  ApiResponse.java        — 统一响应 DTO (独立类)              │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                   实现层 (新增)                                 │
│                                                              │
│  ReactAgentRunner.java      — AgentRunner 实现 (封装 ReactAgent│
│                                / SupervisorAgent)             │
│  DashScopeLlmProvider.java  — LlmProvider 实现                │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 异常体系

```
BizException (abstract, extends RuntimeException)
├── ServiceUnavailableException    → 503
├── InvalidInputException           → 400
├── RateLimitExceededException      → 429
├── ResourceNotFoundException       → 404
├── AuthenticationException         → 401
├── LlmServiceException            → 502
└── AgentTimeoutException          → 504

GlobalExceptionHandler (@RestControllerAdvice)
→ 统一转换为 ApiResponse<T>，设置正确的 HTTP Status
```

### 1.3 设计原则

1. **业务代码只依赖接口**：Controller 和 Service 不引用 `DashScopeChatModel`、`ReactAgent`、`SupervisorAgent` 等框架具体类
2. **异常决定 HTTP 状态码**：每个 `BizException` 子类自带 `httpStatus`，`GlobalExceptionHandler` 据此设置响应
3. **中等抽象深度**：流式响应封装为自定义 DTO + Reactor `Flux`，工具层的 `@Tool` 注解保持不变（可接受耦合）
4. **未知异常脱敏**：未捕获的 `Exception` 统一返回 500 + 通用消息，不暴露内部堆栈

---

## 2. 核心接口层设计

### 2.1 统一响应结构 — `ApiResponse<T>`

替代当前 3 个各自重复定义的内部类（`ChatController.ApiResponse`、`FileUploadController.ApiResponse`、`AuthController.ApiResponse`）。

**包路径**：`org.example.dto.ApiResponse`

```java
package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;           // HTTP 状态码
    private String message;     // 可读消息
    private T data;             // 业务数据
    private long timestamp;     // 响应时间戳 (epoch ms)
    private String requestId;   // 请求追踪 ID（MDC 注入）
    private String path;        // 请求路径
    private PageInfo page;      // 分页信息（预留，无分页时为 null）

    private ApiResponse() {}    // 禁止直接构造

    // --- 工厂方法 ---

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    // --- Builder-style 链式设置 ---

    public ApiResponse<T> withRequestId(String requestId) { this.requestId = requestId; return this; }
    public ApiResponse<T> withPath(String path) { this.path = path; return this; }
    public ApiResponse<T> withPage(int page, int size, long total) {
        this.page = new PageInfo(page, size, total); return this;
    }

    // --- Getters (lombok 可选) ---

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public long getTimestamp() { return timestamp; }
    public String getRequestId() { return requestId; }
    public String getPath() { return path; }
    public PageInfo getPage() { return page; }

    // --- 内部 record ---

    public record PageInfo(int page, int size, long total) {}
}
```

**响应示例**：

```json
{
  "code": 200,
  "message": "success",
  "data": { "answer": "...", "sessionId": "abc123" },
  "timestamp": 1753977600000,
  "requestId": "req-a1b2c3",
  "path": "/api/chat"
}
```

```json
{
  "code": 503,
  "message": "Milvus 向量数据库连接失败，请稍后重试",
  "timestamp": 1753977600000,
  "requestId": "req-a1b2c3",
  "path": "/api/memory/panel"
}
```

**requestId 来源**：由 `LogInterceptor` 在 `preHandle` 中通过 `MDC.put("requestId", UUID.randomUUID().toString())` 设置，`GlobalExceptionHandler` 从 `MDC` 读取后注入响应。

### 2.2 `AgentRunner` 接口

**包路径**：`org.example.agent.AgentRunner`

```java
package org.example.agent;

import reactor.core.publisher.Flux;

/**
 * Agent 执行抽象。
 * 隔离 Spring AI Alibaba Agent 框架（ReactAgent / SupervisorAgent）。
 */
public interface AgentRunner {

    /**
     * 同步执行 Agent 对话。
     * 替代 ReactAgent.call()，用于 /api/chat 非流式端点。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return AI 回复文本
     * @throws LlmServiceException 如果 LLM 调用失败
     */
    String execute(String systemPrompt, String userMessage);

    /**
     * 流式执行 Agent 对话。
     * 替代 agent.stream()，用于 /api/chat_stream SSE 端点。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return AgentEvent 流
     */
    Flux<AgentEvent> executeStream(String systemPrompt, String userMessage);

    /**
     * 执行多 Agent 编排（AIOps 智能运维分析）。
     * 替代 SupervisorAgent.invoke()，用于 /api/ai_ops 端点。
     *
     * @param taskPrompt 任务描述
     * @return AIOps 分析结果
     * @throws AgentTimeoutException 如果分析超时
     */
    AiOpsResult executeOrchestration(String taskPrompt);
}
```

### 2.3 `LlmProvider` 接口

**包路径**：`org.example.agent.LlmProvider`

```java
package org.example.agent;

import reactor.core.publisher.Flux;

/**
 * LLM Provider 抽象。
 * 隔离 DashScopeChatModel 等具体 LLM 实现。
 */
public interface LlmProvider {

    /**
     * 同步 LLM 调用
     *
     * @param systemMessage 系统消息
     * @param userMessage   用户消息
     * @param options       调用参数
     * @return LLM 回复文本
     * @throws LlmServiceException 如果 LLM API 故障
     */
    String chat(String systemMessage, String userMessage, ChatOptions options);

    /**
     * 流式 LLM 调用
     *
     * @param systemMessage 系统消息
     * @param userMessage   用户消息
     * @param options       调用参数
     * @return 增量文本流
     */
    Flux<String> chatStream(String systemMessage, String userMessage, ChatOptions options);

    /**
     * LLM 调用参数
     */
    record ChatOptions(String model, double temperature, int maxToken, double topP) {

        /** 标准对话参数 */
        public static ChatOptions standard(String model) {
            return new ChatOptions(model, 0.7, 2000, 0.9);
        }

        /** AIOps 分析参数（低温度，更大输出） */
        public static ChatOptions aiOps(String model) {
            return new ChatOptions(model, 0.3, 8000, 0.9);
        }
    }
}
```

### 2.4 `AgentEvent` — 流式事件 DTO

**包路径**：`org.example.dto.AgentEvent`

```java
package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEvent {

    private EventType type;    // 事件类型
    private String data;       // 文本内容 或 错误信息
    private String sessionId;  // 会话 ID（仅 DONE 事件携带）

    public enum EventType {
        /** 文本增量 */
        @com.fasterxml.jackson.annotation.JsonValue("content")
        CONTENT_CHUNK,
        /** 工具调用开始（含工具名） */
        @com.fasterxml.jackson.annotation.JsonValue("tool_start")
        TOOL_CALL_START,
        /** 工具调用完成 */
        @com.fasterxml.jackson.annotation.JsonValue("tool_end")
        TOOL_CALL_END,
        /** 错误 */
        @com.fasterxml.jackson.annotation.JsonValue("error")
        ERROR,
        /** 流结束 */
        @com.fasterxml.jackson.annotation.JsonValue("done")
        DONE
    }

    // --- 工厂方法 ---

    public static AgentEvent content(String chunk) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.CONTENT_CHUNK;
        e.data = chunk;
        return e;
    }

    public static AgentEvent toolCallStart(String toolName) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TOOL_CALL_START;
        e.data = toolName;
        return e;
    }

    public static AgentEvent toolCallEnd(String toolName) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TOOL_CALL_END;
        e.data = toolName;
        return e;
    }

    public static AgentEvent error(String errorMessage) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.ERROR;
        e.data = errorMessage;
        return e;
    }

    public static AgentEvent done(String sessionId) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.DONE;
        e.sessionId = sessionId;
        return e;
    }

    // --- Getters ---

    public EventType getType() { return type; }
    public String getData() { return data; }
    public String getSessionId() { return sessionId; }
}
```

### 2.5 `AiOpsResult` — AIOps 结果 DTO

**包路径**：`org.example.dto.AiOpsResult`

替代 `Optional<OverAllState>`，去除对框架状态容器的依赖。

```java
package org.example.dto;

public class AiOpsResult {

    private boolean success;          // 是否正常完成
    private String finalReport;       // 最终报告文本
    private int rounds;              // 实际执行轮数
    private boolean timeoutFallback; // 是否为超时兜底报告
    private String errorMessage;     // 失败原因（success=false 时）

    // --- 工厂方法 ---

    public static AiOpsResult success(String report, int rounds) {
        AiOpsResult r = new AiOpsResult();
        r.success = true;
        r.finalReport = report;
        r.rounds = rounds;
        return r;
    }

    public static AiOpsResult timeoutFallback(String report) {
        AiOpsResult r = new AiOpsResult();
        r.success = true;
        r.finalReport = report;
        r.timeoutFallback = true;
        return r;
    }

    public static AiOpsResult failed(String errorMessage) {
        AiOpsResult r = new AiOpsResult();
        r.success = false;
        r.errorMessage = errorMessage;
        return r;
    }

    // --- Getters ---

    public boolean isSuccess() { return success; }
    public String getFinalReport() { return finalReport; }
    public int getRounds() { return rounds; }
    public boolean isTimeoutFallback() { return timeoutFallback; }
    public String getErrorMessage() { return errorMessage; }
}
```

---

## 3. 实现层设计

### 3.1 `ReactAgentRunner` — AgentRunner 实现

**包路径**：`org.example.agent.ReactAgentRunner`

核心职责：封装 Spring AI Alibaba `ReactAgent` 和 `SupervisorAgent` 的创建与执行。

```java
package org.example.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.example.dto.AgentEvent;
import org.example.dto.AiOpsResult;
import org.example.exception.LlmServiceException;
import org.example.exception.AgentTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.*;

@Component
public class ReactAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentRunner.class);

    @Autowired
    private LlmProvider llmProvider;

    @Autowired
    private ToolCallbackProvider tools;

    // ... 各工具组件注入（从 ChatService 迁入）

    // === 同步执行 ===

    @Override
    public String execute(String systemPrompt, String userMessage) {
        ReactAgent agent = buildReactAgent(systemPrompt);
        try {
            var response = agent.call(userMessage);
            return response.getText();
        } catch (Exception e) {
            throw new LlmServiceException("DashScope", "Agent 执行失败: " + e.getMessage());
        }
    }

    // === 流式执行 ===

    @Override
    public Flux<AgentEvent> executeStream(String systemPrompt, String userMessage) {
        ReactAgent agent = buildReactAgent(systemPrompt);
        return Flux.create(sink -> {
            try {
                agent.stream(userMessage).subscribe(
                    output -> {
                        if (output instanceof StreamingOutput so) {
                            OutputType type = so.getOutputType();
                            if (type == OutputType.AGENT_MODEL_STREAMING) {
                                String chunk = so.message().getText();
                                if (chunk != null && !chunk.isEmpty()) {
                                    sink.next(AgentEvent.content(chunk));
                                }
                            } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                sink.next(AgentEvent.toolCallEnd(so.node()));
                            }
                        }
                    },
                    error -> sink.next(AgentEvent.error(error.getMessage())),
                    () -> sink.complete()
                );
            } catch (Exception e) {
                sink.next(AgentEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    // === 多 Agent 编排 ===

    @Override
    public AiOpsResult executeOrchestration(String taskPrompt) {
        // 内部构建 Planner + Executor ReactAgent → SupervisorAgent
        // 超时控制 + 兜底报告生成
        // 从 AiOpsService 迁移逻辑
        // ...

        // 正常完成返回 AiOpsResult.success(report, rounds)
        // 超时返回 AiOpsResult.timeoutFallback(report)
        // 失败返回 AiOpsResult.failed(errorMsg)
    }

    // === 私有方法 ===

    private ReactAgent buildReactAgent(String systemPrompt) {
        // 从 ChatService.createReactAgent() 迁移
        // 工具构建逻辑从 ChatService.buildMethodToolsArray() 迁移
        // ...
    }

    private ReactAgent buildPlannerAgent(String systemPrompt) { /* ... */ }
    private ReactAgent buildExecutorAgent(String systemPrompt) { /* ... */ }
}
```

**迁移要点**：
- `ChatService.createDashScopeApi()` / `createChatModel()` / `createStandardChatModel()` → 删除，由 `DashScopeLlmProvider` 接管
- `ChatService.createReactAgent()` → 迁移为 `ReactAgentRunner` 的私有方法
- `ChatService.buildMethodToolsArray()` → 迁移为 `ReactAgentRunner` 的私有方法
- `ChatService.executeChat()` → 替换为 `AgentRunner.execute()`
- `AiOpsService` 中的 Agent 构建和执行逻辑 → 整体迁入 `ReactAgentRunner.executeOrchestration()`
- `ChatService` 瘦身为：系统提示词构建 + 记忆注入（纯业务逻辑，无框架依赖）

### 3.2 `DashScopeLlmProvider` — LlmProvider 实现

**包路径**：`org.example.agent.DashScopeLlmProvider`

```java
package org.example.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.example.exception.LlmServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class DashScopeLlmProvider implements LlmProvider {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Override
    @CircuitBreaker(name = "dashscope-llm", fallbackMethod = "chatFallback")
    public String chat(String systemMessage, String userMessage, ChatOptions options) {
        DashScopeChatModel model = buildModel(options);
        // 构建 Prompt 并调用
        // ...
    }

    @Override
    public Flux<String> chatStream(String systemMessage, String userMessage, ChatOptions options) {
        DashScopeChatModel model = buildModel(options);
        // 流式调用并返回 Flux<String>
        // ...
    }

    // === 降级 ===

    private String chatFallback(String systemMessage, String userMessage, ChatOptions options, Throwable t) {
        log.warn("[CircuitBreaker] LLM 服务降级 - error: {}", t.getMessage());
        throw new LlmServiceException("DashScope", "AI 服务暂时不可用，系统已自动熔断保护，预计 30 秒后恢复");
    }

    private DashScopeChatModel buildModel(ChatOptions options) {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(options.model())
                        .withTemperature(options.temperature())
                        .withMaxToken(options.maxToken())
                        .withTopP(options.topP())
                        .build())
                .build();
    }
}
```

**要点**：
- `ChatOptions.model` 默认为 `DashScopeChatModel.DEFAULT_MODEL_NAME`（即 `qwen3-max`）
- `@CircuitBreaker` 注解保持在实现层，不暴露给调用方
- 降级方法抛 `LlmServiceException` 替代返回友好消息字符串——Controller 层通过 `GlobalExceptionHandler` 统一处理

---

## 4. 异常体系设计

### 4.1 异常层次

**包路径**：`org.example.exception`

```
BizException (abstract)
├── ServiceUnavailableException
├── InvalidInputException
├── RateLimitExceededException
├── ResourceNotFoundException
├── AuthenticationException
├── LlmServiceException
└── AgentTimeoutException
```

### 4.2 `BizException` — 抽象基类

```java
package org.example.exception;

/**
 * 业务异常抽象基类。
 * 所有自定义异常必须继承此类，GlobalExceptionHandler 据此统一处理。
 */
public abstract class BizException extends RuntimeException {

    private final String errorCode;  // 机器可读错误码
    private final int httpStatus;    // HTTP 状态码

    protected BizException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    protected BizException(String errorCode, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}
```

### 4.3 各子类定义

```java
// === ServiceUnavailableException → 503 ===
public class ServiceUnavailableException extends BizException {
    public ServiceUnavailableException(String serviceName, String detail) {
        super(serviceName.toUpperCase() + "_UNAVAILABLE", 503,
              serviceName + " 服务不可用: " + detail);
    }
}

// === InvalidInputException → 400 ===
public class InvalidInputException extends BizException {
    public InvalidInputException(String message) {
        super("INVALID_INPUT", 400, message);
    }
}

// === RateLimitExceededException → 429 ===
public class RateLimitExceededException extends BizException {
    public RateLimitExceededException(String message) {
        super("RATE_LIMITED", 429, message);
    }
}

// === ResourceNotFoundException → 404 ===
public class ResourceNotFoundException extends BizException {
    public ResourceNotFoundException(String resourceType, String identifier) {
        super(resourceType.toUpperCase() + "_NOT_FOUND", 404,
              resourceType + " 不存在: " + identifier);
    }
}

// === AuthenticationException → 401 ===
public class AuthenticationException extends BizException {
    public AuthenticationException(String message) {
        super("AUTH_FAILED", 401, message);
    }
}

// === LlmServiceException → 502 ===
public class LlmServiceException extends BizException {
    public LlmServiceException(String provider, String detail) {
        super("LLM_UNAVAILABLE", 502,
              provider + " LLM 服务异常: " + detail);
    }
}

// === AgentTimeoutException → 504 ===
public class AgentTimeoutException extends BizException {
    public AgentTimeoutException(int timeoutSeconds) {
        super("AGENT_TIMEOUT", 504,
              "分析超时 (" + timeoutSeconds + " 秒)，已生成基于知识推断的兜底报告");
    }
}
```

### 4.4 异常与 HTTP 状态码映射

| 异常类 | HTTP | errorCode | 典型场景 |
|--------|------|-----------|---------|
| `InvalidInputException` | 400 | `INVALID_INPUT` | 参数为空、格式错误 |
| `AuthenticationException` | 401 | `AUTH_FAILED` | API Key 无效 |
| `ResourceNotFoundException` | 404 | `NOT_FOUND` | 会话/文档/记忆不存在 |
| `RateLimitExceededException` | 429 | `RATE_LIMITED` | 触发限流 |
| `LlmServiceException` | 502 | `LLM_UNAVAILABLE` | LLM API 故障/断路器打开 |
| `ServiceUnavailableException` | 503 | `MILVUS_UNAVAILABLE` / `REDIS_UNAVAILABLE` | 基础设施故障 |
| `AgentTimeoutException` | 504 | `AGENT_TIMEOUT` | AIOps 分析超时 |
| 未捕获 `Exception` | 500 | `INTERNAL_ERROR` | 未知错误（消息脱敏） |

---

## 5. 全局异常处理器

**包路径**：`org.example.exception.GlobalExceptionHandler`

```java
package org.example.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：根据异常的 httpStatus 设置 HTTP 响应码
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest req) {
        log.warn("[BizException] path={} errorCode={} message={}",
                req.getRequestURI(), ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getHttpStatus(), ex.getMessage())
                        .withRequestId(MDC.get("requestId"))
                        .withPath(req.getRequestURI()));
    }

    /**
     * 参数校验异常（@Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("参数校验失败");
        log.warn("[Validation] path={} errors={}", req.getRequestURI(), detail);
        return ResponseEntity.status(400)
                .body(ApiResponse.error(400, "参数校验失败: " + detail)
                        .withRequestId(MDC.get("requestId"))
                        .withPath(req.getRequestURI()));
    }

    /**
     * 未知异常：脱敏处理，不暴露内部细节
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("[Unhandled Error] path={} type={} message={}",
                req.getRequestURI(), ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(500, "系统内部错误，请稍后重试")
                        .withRequestId(MDC.get("requestId"))
                        .withPath(req.getRequestURI()));
    }
}
```

---

## 6. 异步异常处理

### 6.1 `AsyncConfig` 补充

当前 `AsyncConfig` 未实现 `AsyncConfigurer`，需要补充 `AsyncUncaughtExceptionHandler` 作为异步任务异常的兜底安全网。

```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            log.error("[Async Error] method={} params={}",
                     method.getName(), params, ex);
            // 预留：后续可接入告警通知
        };
    }

    // ... 现有线程池 Bean 保持不变
}
```

**注意**：现有的 `MemoryExtractor.extractAsync()` 和 `SummaryGenerator.triggerAsync()` 内部已有 try-catch，此 handler 作为兜底机制——防止未来新增的 `@Async` 方法忘记加错误处理导致静默丢失。

---

## 7. Controller 层迁移指南

### 7.1 迁移前后对比（ChatController.chat()）

```java
// === 迁移前 ===
@PostMapping("/chat")
public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
    try {
        if (request.getQuestion() == null || ...) {
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题不能为空")));
        }
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
        String answer = chatService.executeChat(agent, request.getQuestion());
        return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(answer, sessionId)));
    } catch (Exception e) {
        return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
    }
}

// === 迁移后 ===
@PostMapping("/chat")
public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
    if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
        throw new InvalidInputException("问题内容不能为空");
    }
    IntentResult intent = intentRouter.classify(request.getQuestion());
    if (intent.getCategory() == IntentCategory.ALERT_DIAGNOSIS) {
        return handleAIOpsRoute(request);
    }
    String userId = getCurrentUserId();
    RecallMemoryTool.setCurrentUserId(userId);
    try {
        SessionManager.SessionContext ctx = sessionManager.getOrCreateSession(request.getId());
        String systemPrompt = chatService.buildSystemPrompt(
                ctx.hasSummary() ? Collections.emptyList() : ctx.getHistory(),
                ctx.getSummary(), userId);
        String answer = agentRunner.execute(systemPrompt, request.getQuestion());
        sessionManager.addMessage(ctx.getSessionId(), request.getQuestion(), answer, userId);
        return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(answer, ctx.getSessionId())));
    } finally {
        RecallMemoryTool.clearCurrentUserId();
    }
}
```

**关键变化**：
- 删除 `try-catch (Exception e)` 整个包裹 → 异常自动穿透到 `GlobalExceptionHandler`
- 删除 `DashScopeApi`/`DashScopeChatModel`/`ReactAgent` 的创建 → 替换为 `agentRunner.execute()`
- 参数校验从返回错误体 → 抛 `InvalidInputException`
- 保留 `finally` 清理 ThreadLocal（不受异常处理影响）

### 7.2 各 Controller 变更汇总

| Controller | 当前模式 | 变更后 |
|-----------|---------|--------|
| `ChatController` | 每方法 try-catch，返回 200 + 错误体；直接创建框架对象 | 抛 BizException，无 try-catch；调用 agentRunner |
| `MemoryController` | 部分无 try-catch；Map 响应 | 统一 ApiResponse；参数校验抛异常 |
| `FileUploadController` | 多层 try-catch，吞异常 | 抛 BizException；文件校验前置，索引失败不影响上传成功 |
| `AuthController` | 无 try-catch（较好） | 返回类型改为 ApiResponse |
| `MilvusCheckController` | try-catch 返回 503 | 保持逻辑，返回类型改为 ApiResponse |

### 7.3 流式端点迁移（ChatController.chatStream()）

```java
// === 迁移后 ===
@PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chatStream(@RequestBody ChatRequest request) {
    SseEmitter emitter = new SseEmitter(300000L);

    if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
        // 校验失败直接抛异常 — 但由于返回类型是 SseEmitter 不可用 @ExceptionHandler
        // 所以 SSE 入口保持前置校验 + 立即 emitter.completeWithError
        // ...
    }

    String userId = getCurrentUserId();
    executor.execute(() -> {
        RecallMemoryTool.setCurrentUserId(userId);
        try {
            SessionManager.SessionContext ctx = sessionManager.getOrCreateSession(request.getId());
            String systemPrompt = chatService.buildSystemPrompt(...);
            StringBuilder fullAnswerBuilder = new StringBuilder();

            Flux<AgentEvent> stream = agentRunner.executeStream(systemPrompt, request.getQuestion());
            stream.subscribe(
                event -> {
                    switch (event.getType()) {
                        case CONTENT_CHUNK -> {
                            fullAnswerBuilder.append(event.getData());
                            emitter.send(SseEmitter.event()
                                .name("message").data(event, MediaType.APPLICATION_JSON));
                        }
                        case ERROR -> {
                            emitter.send(SseEmitter.event()
                                .name("message").data(event, MediaType.APPLICATION_JSON));
                        }
                        case DONE -> {
                            sessionManager.addMessage(ctx.getSessionId(), request.getQuestion(),
                                fullAnswerBuilder.toString(), userId);
                            emitter.send(SseEmitter.event()
                                .name("message").data(event, MediaType.APPLICATION_JSON));
                            emitter.complete();
                        }
                    }
                },
                error -> emitter.completeWithError(error)
            );
        } catch (Exception e) {
            log.error("Agent 流式执行失败", e);
            emitter.completeWithError(e);
        } finally {
            RecallMemoryTool.clearCurrentUserId();
        }
    });
    return emitter;
}
```

**关键变化**：
- `agent.stream()` → `agentRunner.executeStream()`，返回 `Flux<AgentEvent>` 替代 `Flux<NodeOutput>`
- `output instanceof StreamingOutput` / `OutputType.AGENT_MODEL_STREAMING` 等框架判断 → 直接 switch `event.getType()`
- AgentEvent 是纯 DTO，可安全序列化为 JSON

---

## 8. 流式抽象设计

### 8.1 框架事件到 AgentEvent 的映射

`ReactAgentRunner.executeStream()` 内部负责将 Spring AI Alibaba 的流式事件映射为通用 `AgentEvent`：

| 框架事件 (Input) | AgentEvent (Output) |
|------------------|---------------------|
| `StreamingOutput` + `OutputType.AGENT_MODEL_STREAMING` | `AgentEvent.content(chunk)` |
| `OutputType.AGENT_TOOL_FINISHED` | `AgentEvent.toolCallEnd(nodeName)` |
| 流 subscriber onError | `AgentEvent.error(msg)` |
| 流 subscriber onComplete | `agent` 内部 emit `AgentEvent.done(sessionId)` |

### 8.2 SSE 事件类型对照（前端兼容）

`AgentEvent` 使用 `@JsonValue` 让枚举序列化为小写格式，与现有前端 `app.js` 中的 `switch(message.type)` 逻辑（`content` / `error` / `done`）完全兼容，**前端无需改动**。

枚举序列化映射：

```java
public enum EventType {
    @JsonValue("content")  CONTENT_CHUNK,
    @JsonValue("tool_start") TOOL_CALL_START,
    @JsonValue("tool_end")   TOOL_CALL_END,
    @JsonValue("error")    ERROR,
    @JsonValue("done")     DONE;
}
```

| SSE event name | 序列化后的 type | data 内容 |
|---------------|----------------|-----------|
| `message` | `"content"` | `{"type":"content","data":"..."}` |
| `message` | `"tool_end"` | `{"type":"tool_end","data":"..."}` |
| `message` | `"error"` | `{"type":"error","data":"..."}` |
| `message` | `"done"` | `{"type":"done","sessionId":"abc123"}` |

现有前端代码中 `message.type === 'content'` / `'error'` / `'done'` 的判断完全兼容。新增 `tool_start` / `tool_end` 事件类型前端可后续利用，当前静默忽略即可。

### 8.3 不可变的耦合范围

以下框架相关项**不抽象**，视为可接受耦合：

| 项 | 理由 |
|----|------|
| `@Tool` / `@ToolParam` 注解 | 标记性注解，不影响业务逻辑，且不同框架的工具定义语义差异大 |
| `Flux` (Project Reactor) | Spring 生态标准，非供应商特定 |
| `ToolCallbackProvider` / `ToolCallback[]` | Spring AI 标准接口，非 Alibaba 特定 |
| `SseEmitter` | Spring MVC 标准 |

---

## 9. 配置设计

### 9.1 `application.yml` 新增配置

```yaml
# ============================================
# Agent 抽象配置
# ============================================
superbiz:
  agent:
    # 默认 LLM 模型
    model:
      chat: qwen3-max           # 标准对话
      lightweight: qwen-turbo   # 摘要、提取、意图识别
      aiops: qwen3-max          # AIOps 分析

    # AIOps 超时控制
    aiops:
      total-timeout-seconds: 300
      max-rounds: 10
```

### 9.2 无需新增依赖

`Flux` / `Project Reactor` 已通过 `spring-ai-starter-mcp-client-webflux` 间接引入（`spring-webflux` 传递依赖 `reactor-core`）。无需要额外 Maven 依赖。

---

## 10. 文件清单

### 10.1 新增文件

```
核心接口层 (5 个):
  src/main/java/org/example/agent/
    AgentRunner.java              — Agent 执行抽象接口
    LlmProvider.java              — LLM Provider 抽象接口
  src/main/java/org/example/dto/
    AgentEvent.java               — 流式事件 DTO
    AiOpsResult.java              — AIOps 结果 DTO
    ApiResponse.java              — 统一响应 DTO（独立类）

实现层 (2 个):
  src/main/java/org/example/agent/
    ReactAgentRunner.java         — AgentRunner 实现（封装 ReactAgent/SupervisorAgent）
    DashScopeLlmProvider.java     — LlmProvider 实现

异常体系 (8 个):
  src/main/java/org/example/exception/
    BizException.java             — 抽象基类
    ServiceUnavailableException.java
    InvalidInputException.java
    RateLimitExceededException.java
    ResourceNotFoundException.java
    AuthenticationException.java
    LlmServiceException.java
    AgentTimeoutException.java
    GlobalExceptionHandler.java   — @RestControllerAdvice

合计: 15 个新增文件
```

### 10.2 修改文件

```
  src/main/java/org/example/
    controller/ChatController.java         — 移除框架引用 + try-catch → 抛异常
    controller/MemoryController.java       — Map → ApiResponse + 异常
    controller/FileUploadController.java   — 统一 ApiResponse
    controller/AuthController.java         — 返回类型改为 ApiResponse
    controller/MilvusCheckController.java  — 返回类型改为 ApiResponse
    service/ChatService.java               — 瘦身为提示词构建 + 记忆注入
    service/AiOpsService.java              — Agent 构建迁入 ReactAgentRunner
    config/AsyncConfig.java                — 补充 AsyncUncaughtExceptionHandler
    interceptor/LogInterceptor.java         — MDC 注入 requestId

合计: 9 个修改文件
```

### 10.3 可删除的内部类

```
  ChatController.java:
    - ApiResponse<T> 内部类
    - SseMessage 内部类 (被 AgentEvent 替代)
    - ChatResponse 内部类 (保留、简化)
  FileUploadController.java:
    - ApiResponse 内部类
  AuthController.java:
    - ApiResponse 内部类 (如果有)
```

---

## 11. 测试策略

### 11.1 单元测试

| 测试类 | 覆盖内容 |
|--------|---------|
| `BizExceptionTest` | 各子类 errorCode / httpStatus 正确性 |
| `GlobalExceptionHandlerTest` | 各异常 → 正确 HTTP 状态码 / 响应体格式 |
| `ApiResponseTest` | 工厂方法正确性、分页/requestId/path 链式调用 |
| `AgentEventTest` | 各 EventType 工厂方法 |
| `AiOpsResultTest` | success / timeoutFallback / failed 工厂方法 |
| `AsyncConfigTest` | UncaughtExceptionHandler 不为 null |

### 11.2 集成测试

| 测试类 | 覆盖内容 |
|--------|---------|
| `GlobalExceptionHandlerIntegrationTest` | `@SpringBootTest` + `MockMvc`，模拟各异常场景，验证 HTTP 状态码和响应 JSON |
| `ReactAgentRunnerIntegrationTest` | 注入 `ReactAgentRunner`，执行 `execute()` / `executeStream()` 验证正常流程 |
| `DashScopeLlmProviderIntegrationTest` | 模拟 LLM 调用，验证降级行为 |
| `ChatControllerIntegrationTest` | 正常对话 → 200，空参数 → 400，异常 → 正确错误码 |

### 11.3 手工测试

| 场景 | 步骤 |
|------|------|
| 正常对话 | POST /api/chat → 200 + answer |
| 空问题 | POST /api/chat `Question=""` → 400 + INVALID_INPUT |
| 会话不存在 | GET /api/chat/session/not-exist → 404 |
| 断路器降级 | 断开 DashScope → 502 + LLM_UNAVAILABLE |
| 流式对话 | POST /api/chat_stream → SSE 事件流正常推送 |
| SSE 错误事件 | 流中触发工具失败 → ERROR 事件 |

---

## 附录 A：不可变的耦合范围（明确声明）

| 框架项 | 范围 | 理由 |
|--------|------|------|
| `@Tool` / `@ToolParam` 注解 | 所有 tool 类 | 标记性注解，不影响业务逻辑；不同框架的工具语义差异大，无意义抽象 |
| `ToolCallbackProvider` / `ToolCallback` | ReactAgentRunner 内部 | Spring AI 标准接口，非 Alibaba 特定 |
| `Flux` / `Reactor` | AgentRunner 接口 | Spring 生态标准，非供应商特定 |
| `SseEmitter` | Controller | Spring MVC 标准 |
| `@CircuitBreaker` 注解 | 实现层 | Resilence4j 是独立库，非框架耦合 |

## 附录 B：与现有安全认证设计的兼容

本设计中的 `ApiResponse<T>` 将替代安全设计文档（`2026-07-27-security-auth-design.md`）中第 6 节使用的临时响应格式。`GlobalExceptionHandler` 将接管 `SecurityConfig` 中的 `AuthenticationEntryPoint` 部分 401/403 响应格式，确保所有错误响应统一使用 `ApiResponse<T>` 格式。

## 附录 C：Controller 不再写 try-catch 的验收标准

- [ ] 所有 Controller 方法体内无 `try { ... } catch (Exception e) { ... }` 块
- [ ] 参数校验失败 → 抛 `InvalidInputException`
- [ ] 依赖服务不可用 → 抛 `ServiceUnavailableException`
- [ ] LLM 故障 → 抛 `LlmServiceException`
- [ ] 资源不存在 → 抛 `ResourceNotFoundException`
- [ ] `finally` 块仅用于 ThreadLocal 清理，不包含错误处理逻辑
