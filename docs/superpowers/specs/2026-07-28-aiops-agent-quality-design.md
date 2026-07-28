# SuperBizAgent AIOps Agent 质量保障 — 技术设计文档

> 版本：v1.0 | 日期：2026-07-28 | 基于改进方案 [P1-5](../../session/idea/2026-07-22-superbizagent-improvement-plan.md#5-aiops-agent-质量保障)

---

## 目录

1. [架构概述](#1-架构概述)
2. [意图识别路由](#2-意图识别路由)
3. [测试用例集](#3-测试用例集)
4. [LLM-as-Judge 评估](#4-llm-as-judge-评估)
5. [Mock 数据注入](#5-mock-数据注入)
6. [配置设计](#6-配置设计)
7. [错误处理](#7-错误处理)
8. [文件清单](#8-文件清单)
9. [测试策略](#9-测试策略)

---

## 1. 架构概述

### 1.1 三大子系统关系

```
用户请求 → ChatController
              │
              ▼
     ┌──────────────────┐
     │  ① 意图识别路由   │ ← qwen-turbo 分类
     │     IntentRouter  │
     └──┬───┬───┬───────┘
        │   │   │
        ▼   ▼   ▼
    告警排查  知识检索  通用对话/不明确
        │   │   │
        ▼   ▼   ▼
    AiOpsService  ChatService  ChatService
    (Supervisor)  (ReactAgent  (ReactAgent
                  + RAG)       无检索)
        │
        ▼
    输出告警分析报告
        │
        ▼
     ┌──────────────────┐
     │  ② LLM-as-Judge   │ ← qwen-turbo 评分
     │    AIOpsEvaluator │
     └──────────────────┘
        │
        ▼
    评分 → 日志 + Micrometer 指标
        ▲
        │
     ┌──────────────────┐
     │  ③ 测试用例集     │ ← 评估标准 + Mock 数据源
     │  aiops-test-cases │
     └──────────────────┘
```

### 1.2 模块依赖关系

```
agent/
├── router/
│   ├── IntentRouter.java            — 意图分类服务
│   ├── IntentCategory.java          — 意图类别枚举
│   └── IntentResult.java            — 分类结果 DTO

├── eval/
│   ├── AIOpsEvaluator.java          — LLM-as-Judge 评估服务
│   ├── AIOpsEvalResult.java         — 评估结果 DTO
│   ├── EvalDimension.java           — 评估维度枚举
│   └── TestCaseLoader.java          — 测试用例加载器（解析 Markdown frontmatter）

mock/
│   └── MockDataProvider.java        — Mock 数据注入器（仅 test profile）

controller/
└── ChatController.java              — 【修改】集成 IntentRouter 调用
```

---

## 2. 意图识别路由

### 2.1 组件：IntentRouter

新增轻量服务 `IntentRouter`，在 `ChatController` 层被调用，位于 Agent 创建之前。

**位置**：`agent/router/IntentRouter.java`

**核心方法**：

```java
@Service
public class IntentRouter {

    /**
     * 对用户输入进行意图分类
     * @param userInput 用户原始文本
     * @return 分类结果（类别 + 置信度）
     */
    public IntentResult classify(String userInput) {
        // 1. 调用 qwen-turbo 做意图识别
        // 2. 解析 JSON 响应 → IntentCategory + confidence
        // 3. confidence < threshold → UNCLEAR
        // 4. 调用异常 → 降级为 GENERAL_CHAT
    }
}
```

### 2.2 意图类别（IntentCategory）

```java
public enum IntentCategory {
    ALERT_DIAGNOSIS,    // 告警排查 → AiOpsService
    KNOWLEDGE_RETRIEVAL, // 知识检索 → ReactAgent + queryInternalDocs
    GENERAL_CHAT,       // 通用对话 → ReactAgent（无检索工具）
    UNCLEAR             // 意图不明确 → 通用 Chat + 引导追问
}
```

### 2.3 Controller 集成（ChatController 修改）

```
POST /api/chat (非流式)
POST /api/chat_stream (SSE 流式)
```

两个端点在收到 `userInput` 后，调用 `IntentRouter.classify(userInput)`：

```
if (category == ALERT_DIAGNOSIS) {
    → 委托给 AiOpsService.executeAiOpsAnalysis()
} else if (category == KNOWLEDGE_RETRIEVAL) {
    → 构建 RevelationAgent + queryInternalDocs + 知识检索 SystemPrompt
} else if (category == GENERAL_CHAT || UNCLEAR) {
    → 构建标准 ReactAgent（无 RAG 工具）
    → UNCLEAR 时 SystemPrompt 加指令："请先澄清用户意图，引导其描述具体问题"
}
```

**重要**：现有独立端点 `POST /api/ai_ops` 保持不变，不受路由影响。

### 2.4 分类 Prompt

传给 qwen-turbo 的 System Prompt（约 200 token）：

```
分析以下用户输入，判断意图类别，返回JSON格式。

类别定义：
- ALERT_DIAGNOSIS：用户描述系统故障、告警、异常，需要运维排查和分析
  示例："CPU飙了帮我看看"、"Prometheus告警了"、"服务挂了"
- KNOWLEDGE_RETRIEVAL：用户询问公司内部文档、流程、最佳实践或技术方案
  示例："CPU高怎么处理"、"数据库连接池满了怎么办"
- GENERAL_CHAT：通用对话、代码编写、概念解释等不涉及运维排查的请求
  示例："帮我写个监控脚本"、"什么是PromQL"、"你好"
- UNCLEAR：无法归类到以上任意一类的请求

返回格式：{"category": "类别名", "confidence": 0.0-1.0}
```

### 2.5 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `intent.router.enabled` | `true` | 是否启用意图路由 |
| `intent.router.model` | `qwen-turbo` | 分类模型 |
| `intent.router.confidence-threshold` | `0.85` | 低于此值归类为 UNCLEAR |

---

## 3. 测试用例集

### 3.1 目录结构

```
aiops-test-cases/
├── cpu_high_usage.md            # CPU 使用率过高（已有知识库场景）
├── memory_high_usage.md         # 内存使用率过高（已有知识库场景）
├── disk_high_usage.md           # 磁盘使用率过高（已有知识库场景）
├── service_unavailable.md       # 服务不可用（已有知识库场景）
├── slow_response.md             # 响应缓慢（已有知识库场景）
├── db_connection_pool_full.md   # 新增：数据库连接池耗尽
├── mq_consumer_lag.md           # 新增：消息队列积压
├── k8s_pod_crashloop.md         # 新增：K8s Pod CrashLoopBackOff
├── ssl_cert_expiring.md         # 新增：SSL 证书即将过期
└── api_timeout_cascade.md       # 新增：API 超时雪崩
│
└── mock-data/
    ├── cpu-error-logs.json
    ├── db-connection-pool-alerts.json
    ├── db-slow-query-logs.json
    └── ...
```

### 3.2 文件格式

每个 `.md` 文件使用 YAML frontmatter 定义元数据，正文为场景描述。

**示例**（`db_connection_pool_full.md`）：

```markdown
---
id: db_connection_pool_full
severity: 严重
expected_root_causes:
  - 数据库连接池耗尽
  - 慢SQL导致连接堆积
  - 连接池配置过小
critical_evidence:
  - "连接池耗尽"
  - "Cannot get JDBC Connection"
  - "Connection timeout"
mock_alerts:
  - HighDatabaseConnectionUsage
mock_logs:
  - database-slow-query-logs
  - database-error-logs
min_score: 12
---

# 场景：数据库连接池耗尽告警

## 告警背景
生产环境核心服务 `order-service` 在 14:30 突然报出大量 500 错误。
Prometheus 触发 `HighDatabaseConnectionUsage` 告警（数据库连接数超过 90%）。
用户反馈下单失败，影响范围正在扩大。

## 告警输入文本
用户通过 AIOps 提交的原始告警描述：
```
order-service 数据库连接池耗尽，大量 500 错误，需要紧急排查
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighDatabaseConnectionUsage` 告警详情
2. 查询数据库慢查询日志 → 发现某 SQL 执行超过 30s
3. 查询应用错误日志 → 发现 "Cannot get JDBC Connection" 错误
4. 分析根因 → 定位到慢 SQL 导致连接堆积
5. 给出修复建议 → 优化 SQL、增加连接池大小、启用查询超时
```

### 3.3 YAML Frontmatter 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 场景唯一标识，对应 mock 数据文件命名 |
| `severity` | string | 严重/紧急/警告 |
| `expected_root_causes` | string[] | 可接受的根因列表，命中任一即通过（刚性判定） |
| `critical_evidence` | string[] | 关键证据点，Judge 据此评估证据充分性（弹性判定） |
| `mock_alerts` | string[] | 引用的 mock Prometheus 告警数据 ID |
| `mock_logs` | string[] | 引用的 mock CLS 日志数据 ID |
| `min_score` | int | 最低通过分数，默认 12 |

### 3.4 Mock 数据文件格式

`aiops-test-cases/mock-data/` 下为 JSON 文件，模拟工具返回数据：

```json
{
  "alerts": {
    "HighDatabaseConnectionUsage": {
      "alertname": "HighDatabaseConnectionUsage",
      "severity": "critical",
      "labels": {
        "instance": "order-db:3306",
        "service": "order-service"
      },
      "annotations": {
        "summary": "数据库连接数超过90%",
        "description": "当前活跃连接 185/200，连接使用率 92.5%"
      },
      "activeAt": "2026-07-28T14:30:00Z"
    }
  },
  "logs": {
    "database-slow-query-logs": [
      {
        "timestamp": "2026-07-28T14:25:00Z",
        "level": "WARN",
        "message": "Slow query detected: SELECT * FROM orders WHERE ... (32.5s)",
        "query": "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at DESC",
        "duration_ms": 32500
      }
    ],
    "database-error-logs": [
      {
        "timestamp": "2026-07-28T14:30:00Z",
        "level": "ERROR",
        "message": "Cannot get JDBC Connection; nested exception is java.sql.SQLException: Pool exhausted"
      }
    ]
  }
}
```

---

## 4. LLM-as-Judge 评估

### 4.1 组件：AIOpsEvaluator

**位置**：`agent/eval/AIOpsEvaluator.java`

**触发时机**：每次 `AiOpsService` 输出报告后，异步调用（不阻塞主流程返回）。

**核心方法**：

```java
@Service
public class AIOpsEvaluator {

    /**
     * 对 AIOps 分析报告进行质量评估
     * @param scenarioId 测试用例 ID（或 null 表示未知场景）
     * @param reportText 完整报告文本
     * @return 评估结果
     */
    public AIOpsEvalResult evaluate(String scenarioId, String reportText) {
        // 1. 如果 scenarioId 不为 null，加载对应的测试用例元数据
        //    → Judge Prompt 包含预期根因 + 关键证据（场景化评判）
        // 2. 如果 scenarioId 为 null（生产环境未知场景）
        //    → 使用通用评估 Prompt（仅评判结构完整性和可操作性，不评判根因准确度）
        // 3. 调用 qwen-turbo 评分
        // 4. 解析 JSON 返回 → AIOpsEvalResult
    }
}
```

### 4.2 评估维度

```java
public enum EvalDimension {
    ROOT_CAUSE_ACCURACY,    // 根因准确度（刚性）
    EVIDENCE_SUFFICIENCY,   // 证据充分性（弹性）
    STRUCTURE_COMPLETENESS, // 报告结构完整性（刚性）
    ACTIONABILITY           // 可操作性（弹性）
}
```

### 4.3 评分标准

| 维度 | 类型 | 1-5 分标准 |
|------|------|-----------|
| **根因准确度** | 刚性 | 5=命中 expected_root_causes；1=完全偏离 |
| **证据充分性** | 弹性 | 5=引用所有 critical_evidence；3=引用部分；1=未引用 |
| **报告结构完整性** | 刚性 | 5=完整四段式模板；3=缺一段；1=非结构化输出 |
| **可操作性** | 弹性 | 5=步骤具体带命令/参数；3=有方向缺细节；1=空泛建议 |

**通过线**：总分 ≥12 分（满分 20）。

### 4.4 Judge Prompt 模板

```
你是 AIOps 分析报告质量评估器。请对以下告警分析报告按4个维度评分(1-5分)。

## 评分标准
- root_cause_accuracy（根因准确度）：报告的根因是否命中预期根因列表中的任一。完全命中=5，部分相关=3，完全偏离=1
- evidence_sufficiency（证据充分性）：报告是否引用了关键证据点。引用≥3条=5，引用1-2条=3，未引用=1
- structure_completeness（报告结构完整性）：是否包含"活跃告警清单→根因分析→处理方案→结论"完整模板。完整=5，缺一段=3，非结构化=1
- actionability（可操作性）：修复步骤是否具体可执行。带命令/参数=5，有方向缺细节=3，空泛=1

## 预期标准
预期根因：{expected_root_causes}
关键证据：{critical_evidence}

## 待评估报告
{report_text}

## 返回JSON格式（仅返回JSON，不要多余文本）
{
  "root_cause_accuracy": N,
  "evidence_sufficiency": N,
  "structure_completeness": N,
  "actionability": N,
  "total_score": N,
  "reasoning": "简述评分理由"
}
```

### 4.5 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `aiops.eval.enabled` | `true` | 是否启用在线评估 |
| `aiops.eval.model` | `qwen-turbo` | 评估模型 |
| `aiops.eval.min-pass-score` | `12` | 低于此分标记为回归（WARN 日志） |
| `aiops.eval.sample-rate` | `1.0` | 采样率 0.0-1.0 |

### 4.6 评估结果处理

- **评分 ≥12**：INFO 日志 `[AIOps Eval] PASS scenario={id} score={total_score}/20`
- **评分 <12**：WARN 日志 `[AIOps Eval] REGRESSION scenario={id} score={total_score}/20 reasoning={reasoning}`
- **异常/超时**：WARN 日志 + 不影响主流程

---

## 5. Mock 数据注入

### 5.1 组件：MockDataProvider

**位置**：`agent/mock/MockDataProvider.java`

**设计原则**：不修改现有 `QueryMetricsTools` 和 `QueryLogsTools` 的代码。通过**条件 Bean** 注入：

```
@Profile("test")  ← 仅在测试 profile 激活
@Component
public class MockDataProvider {
    // 从 aiops-test-cases/mock-data/ 加载 JSON
    // 提供 getMockAlerts(scenarioId) 和 getMockLogs(scenarioId)
}
```

**与现有工具的集成**：

现有工具已支持模拟模式（`prometheus.mock-enabled` / `cls.mock-enabled`）。MockDataProvider 的策略是：

1. **对齐已有的 mock 开关**：在 test profile 下，自动将 `prometheus.mock-enabled=true` 和 `cls.mock-enabled=true`，激活工具自带的 mock 分支。
2. **扩展 mock 数据源**：`QueryMetricsTools` 和 `QueryLogsTools` 的现有 mock 代码返回的是硬编码的通用数据。MockDataProvider 在 test profile 下通过 `@PostConstruct` 将 `aiops-test-cases/mock-data/` 中的场景化数据注入为这两类工具的数据源，使工具根据当前 `scenarioId` 返回对应场景的预设数据。
3. **降级**：若 mock 数据文件不存在，回退到工具自带的硬编码 mock 数据。

### 5.2 双重用途

测试用例集服务于两个场景：

| 场景 | scenarioId | 数据源 | 触发方式 |
|------|-----------|--------|---------|
| **CI 回归测试** | 已知（测试指定） | MockDataProvider 注入 | Maven test phase |
| **生产在线评估** | 通过意图路由 + 告警名称模糊匹配 | 真实 Prometheus/CLS | 每次 AIOps 调用

---

## 6. 配置设计

### 6.1 application.yml 新增项

```yaml
# ===== 意图识别路由 =====
intent:
  router:
    enabled: true
    model: qwen-turbo
    confidence-threshold: 0.85

# ===== AIOps 质量评估 =====
aiops:
  total-timeout-seconds: 300  # 已有配置
  eval:
    enabled: true
    model: qwen-turbo
    min-pass-score: 12
    sample-rate: 1.0
```

### 6.2 Dashboard API Key 要求

上述 `qwen-turbo` 调用复用现有的 `spring.ai.dashscope.api-key` 配置，无需额外 API Key。

---

## 7. 错误处理

### 7.1 IntentRouter 异常降级

| 异常场景 | 降级策略 |
|---------|---------|
| qwen-turbo 调用超时 (5s) | 归类为 GENERAL_CHAT |
| qwen-turbo 返回非法 JSON | 归类为 GENERAL_CHAT |
| qwen-turbo 认证失败 | 归类为 GENERAL_CHAT + ERROR 日志 |
| 置信度 < 0.85 | 归类为 UNCLEAR → 引导追问 |

### 7.2 AIOpsEvaluator 异常降级

| 异常场景 | 降级策略 |
|---------|---------|
| qwen-turbo 调用超时 (10s) | WARN 日志，不影响报告返回 |
| Judge 返回非法 JSON | WARN 日志 + 原始响应记录 |
| 测试用例文件不存在 | INFO 日志，跳过评估 |
| 所有异常 | 静默捕获，不阻塞、不重试 |

### 7.3 断路器保护

IntentRouter 和 AIOpsEvaluator 的 qwen-turbo 调用复用现有 Resilience4j 断路器配置（`dashscope-llm`）。若断路器打开，降级策略同上。

---

## 8. 文件清单

### 8.1 新增文件

```
src/main/java/org/example/
├── agent/router/
│   ├── IntentRouter.java              ★ 新增
│   ├── IntentCategory.java            ★ 新增
│   └── IntentResult.java              ★ 新增
├── agent/eval/
│   ├── AIOpsEvaluator.java            ★ 新增
│   ├── AIOpsEvalResult.java           ★ 新增
│   ├── EvalDimension.java             ★ 新增
│   └── TestCaseLoader.java            ★ 新增
└── agent/mock/
    └── MockDataProvider.java          ★ 新增（@Profile("test")）

aiops-test-cases/                       ★ 新增目录
├── cpu_high_usage.md                   ★ 新增
├── memory_high_usage.md                ★ 新增
├── disk_high_usage.md                  ★ 新增
├── service_unavailable.md              ★ 新增
├── slow_response.md                    ★ 新增
├── db_connection_pool_full.md          ★ 新增
├── mq_consumer_lag.md                  ★ 新增
├── k8s_pod_crashloop.md                ★ 新增
├── ssl_cert_expiring.md                ★ 新增
├── api_timeout_cascade.md              ★ 新增
└── mock-data/
    ├── cpu-error-logs.json             ★ 新增
    ├── memory-high-alerts.json         ★ 新增
    ├── db-connection-pool-alerts.json  ★ 新增
    ├── db-slow-query-logs.json         ★ 新增
    ├── db-error-logs.json              ★ 新增
    └── ...（各场景对应的 mock 数据）    ★ 新增
```

### 8.2 修改文件

```
src/main/java/org/example/
├── controller/ChatController.java      ☆ 修改：集成 IntentRouter 调用
├── service/AiOpsService.java           ☆ 修改：集成 AIOpsEvaluator 调用
└── resources/application.yml           ☆ 修改：新增配置项
```

---

## 9. 测试策略

### 9.1 单元测试

| 测试目标 | 验证内容 |
|---------|---------|
| `IntentRouter` | 各类输入正确分类、边界置信度处理、异常降级 |
| `AIOpsEvaluator` | Judge Prompt 正确组装、评分 JSON 解析、降级逻辑 |
| `TestCaseLoader` | Markdown frontmatter 解析、Mock 数据加载 |
| `MockDataProvider` | Mock 数据正确注入、格式兼容现有工具返回 |

### 9.2 集成测试（`@SpringBootTest` + `@Profile("test")`）

| 测试用例 | 验证内容 |
|---------|---------|
| 端到端路由 + AIOps | 输入告警描述 → IntentRouter 分类 → AiOpsService 执行 → AIOpsEvaluator 评分 |
| 路由降级 | 模拟 qwen-turbo 故障 → 确认降级到 GENERAL_CHAT |
| 评估降级 | 模拟 Judge 故障 → 确认报告正常返回 |
| Mock 数据注入 | 验证 Agent 调用工具时拿到预设数据而非真实 API |

### 9.3 回归测试

新增 prompt 或修改 Agent 逻辑后：
1. 激活 test profile
2. 运行全部 10 个测试用例
3. 通过标准：所有用例 AIOpsEvaluator 评分 ≥12 分
4. 低于 12 分的场景 → 回归告警

---

> **下一步**：设计方案确认后，使用 `/superpowers:writing-plans` 生成详细实施计划。
