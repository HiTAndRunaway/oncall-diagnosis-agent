# 错误处理一致性 & 框架耦合隔离 — 代码改动统计

> 分支: feature/error-handling-framework-isolation
> 日期: 2026-07-30
> 总变更: 26 files, +1544 / -1069

## 新增文件 (16 个)

| 文件 | 新增行数 | 类别 |
|------|---------|------|
| `agent/AgentRunner.java` | 38 | 核心接口 |
| `agent/LlmProvider.java` | 57 | 核心接口 |
| `agent/DashScopeLlmProvider.java` | 84 | 实现层 |
| `agent/ReactAgentRunner.java` | 610 | 实现层（核心） |
| `dto/AgentEvent.java` | 76 | DTO |
| `dto/AiOpsResult.java` | 54 | DTO |
| `dto/ApiResponse.java` | 84 | DTO |
| `exception/BizException.java` | 31 | 异常基类 |
| `exception/AgentTimeoutException.java` | 15 | 异常子类 |
| `exception/AuthenticationException.java` | 11 | 异常子类 |
| `exception/InvalidInputException.java` | 11 | 异常子类 |
| `exception/LlmServiceException.java` | 15 | 异常子类 |
| `exception/RateLimitExceededException.java` | 11 | 异常子类 |
| `exception/ResourceNotFoundException.java` | 15 | 异常子类 |
| `exception/ServiceUnavailableException.java` | 15 | 异常子类 |
| `exception/GlobalExceptionHandler.java` | 67 | 全局异常处理 |
| **小计** | **1,194** | |

## 修改文件 (10 个)

| 文件 | +新增 / -删除 | 净变化 | 变更说明 |
|------|-------------|--------|---------|
| `controller/ChatController.java` | +280 / -341 | **-61** | 移除框架引用 + try-catch → 异常传播 + AgentRunner 调用 |
| `service/AiOpsService.java` | +34 / -312 | **-278** | 瘦身为薄编排层，委托 AgentRunner |
| `service/ChatService.java` | +27 / -161 | **-134** | 移除 9 个框架方法，保留提示词构建 |
| `controller/FileUploadController.java` | +44 / -42 | **+2** | 统一 ApiResponse + 参数校验抛异常 |
| `controller/MemoryController.java` | +65 / -18 | **+47** | Map→ApiResponse + 补充缺失的异常处理 |
| `controller/AuthController.java` | +20 / -35 | **-15** | 统一使用共享 ApiResponse |
| `controller/MilvusCheckController.java` | +8 / -6 | **+2** | 包装 Map 响应为 ApiResponse |
| `config/AsyncConfig.java` | +13 / -0 | **+13** | 新增 AsyncUncaughtExceptionHandler |
| `interceptor/LogInterceptor.java` | +5 / -0 | **+5** | MDC requestId 注入 |
| `service/SummaryGenerator.java` | +4 / -4 | **0** | 直接创建 DashScopeApi（适配 ChatService 瘦身） |
| **小计** | **+500 / -919** | **-419** | |

## 代码量变化汇总

| 维度 | 数值 |
|------|------|
| 新增纯代码行 | +1,544 |
| 删除旧代码行 | -1,069 |
| **净变化** | **+475** |
| 新增文件数 | 16 |
| 修改文件数 | 10 |
| 删除的内部重复类 | 3 个 (ApiResponse x3) + 1 个 (SseMessage) |

## 架构收益

- **Controller 层**: 移除框架类型引用，移除 try-catch(Exception) 块，代码量净减
- **Service 层**: ChatService (-134行) 和 AiOpsService (-278行) 大幅瘦身
- **实现层**: ReactAgentRunner (610行) 集中封装框架逻辑，隔离在其他模块之外
- **异常体系**: 7 个业务异常 → HTTP 状态码映射，统一由 GlobalExceptionHandler 处理
