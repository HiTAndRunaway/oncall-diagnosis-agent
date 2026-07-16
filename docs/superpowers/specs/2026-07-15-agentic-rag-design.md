# Agentic RAG 对话路径升级设计

> 日期：2026-07-15 | 分支：feature/hybrid-recall-rrf | 状态：待实现

## 1. 目标

将现有线性 RAG 管道（改写 → 检索 → 生成）升级为 **Agent 可控的多轮动态检索**，使 Agent 能够：

1. **自主判断检索是否充分** — 评估结果相关性，不足则自动改写查询重试
2. **自动拆解复杂问题** — 对比/分析/多步类问题拆成子问题分别检索
3. **检索参数自适应** — Agent 根据问题类型选择合适的 topK、检索策略
4. **多源信息融合** — 内部知识库可与对话历史、实时数据混合使用

范围限定：**仅升级对话路径**（`ChatController → ChatService → ReactAgent`），AIOps 路径和 `RagService` 直接调用路径保持不变。

## 2. 架构

### 2.1 整体架构

```
ChatController (/api/chat, /api/chat_stream)
    │
    ▼
ChatService.createReactAgent()
    │  systemPrompt（含 Agentic RAG 行为指令）
    │  methodTools（细粒度 RAG 工具集）
    │  tools（MCP 外部工具）
    ▼
ReactAgent ── ReAct 循环 ──┐
    │                        │
    ├─ 1. 规划查询策略        │
    ├─ 2. 检索（可多轮）      │ ← AgenticRagGuard（最大轮次、相关性阈值、降级）
    ├─ 3. 评估结果质量        │
    ├─ 4. 精炼 / 拆解 / 重试  │
    └─ 5. 综合生成最终答案    │
                             │
    细粒度 RAG 工具集 ◄──────┘
```

### 2.2 配置开关

通过单一配置项控制 RAG 模式：

```yaml
rag:
  agentic:
    enabled: true   # true=Agentic RAG（新工具注册，System Prompt 增强）
                    # false=传统 RAG（新工具不注册，完全回退到现有行为）
```

**实现方式**：新工具 Bean 使用 `@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")` 条件注册。`ChatService.buildSystemPrompt()` 根据配置决定是否注入 Agentic RAG 指令块。

### 2.3 分层职责

| 层 | 组件 | 职责 |
|----|------|------|
| 工具层 | 6 个 RAG 工具（含保留 `queryInternalDocs`） | 每个工具做一件事，Agent 自由组合 |
| 护栏层 | `AgenticRagGuard` | 轮次计数、超时保护、兜底策略 |
| 编排层 | ReactAgent ReAct 循环 | Agent 自主决策调用哪些工具、何时停止 |
| 配置层 | `AgenticRagProperties` | 护栏参数 + 开关 |

## 3. 组件清单

### 3.1 新增文件

```
src/main/java/org/example/
├── agent/tool/
│   ├── SearchKnowledgeBaseTool.java     ← 新增：可配置参数的检索工具
│   ├── EvaluateSearchResultsTool.java   ← 新增：相关性评估工具
│   ├── RefineQueryTool.java            ← 新增：查询改写工具
│   ├── DecomposeQuestionTool.java      ← 新增：问题拆解工具
│   └── GetSearchCapabilitiesTool.java  ← 新增：能力查询工具
├── config/
│   └── AgenticRagProperties.java       ← 新增：护栏配置绑定
└── service/
    └── AgenticRagGuard.java            ← 新增：护栏执行器（轮次计数等）
```

### 3.2 修改文件

| 文件 | 变更内容 |
|------|---------|
| `ChatService.java` | `buildMethodToolsArray()` 追加新工具（条件注册）；`buildSystemPrompt()` 追加 Agentic RAG 行为指令（条件注入） |
| `application.yml` | 新增 `rag.agentic.*` 配置块 |

### 3.3 不改的文件

`RagService`、`VectorSearchService`、`QueryRewriteService`、`VectorIndexService`、`ChatController`、`InternalDocsTools`、`AiOpsService`——全部不动。

## 4. 工具集详设

### 4.1 工具全景

| 工具 | 方法签名 | 职责 | 新增/复用 |
|------|---------|------|-----------|
| `queryInternalDocs` | `(query)` | 保留兼容：一次黑盒检索 | 现有，不改 |
| `searchKnowledgeBase` | `(query, topK?)` | 检索（含改写+双路召回+RRF+Rerank），返回结果 + `_meta` | **新增** |
| `evaluateSearchResults` | `(query, resultsJson)` | 用轻量 LLM 逐条评估相关性 0-1 分 | **新增** |
| `refineQuery` | `(query, feedback)` | 根据评估反馈改写查询 | **新增** |
| `decomposeQuestion` | `(question)` | 拆解复杂问题为子问题列表 | **新增** |
| `getSearchCapabilities` | `(无)` | 告知 Agent 可用检索能力 | **新增** |

### 4.2 SearchKnowledgeBaseTool

```java
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class SearchKnowledgeBaseTool {

    @Autowired private VectorSearchService vectorSearchService;
    @Autowired private QueryRewriteService queryRewriteService;
    @Autowired private AgenticRagGuard guard;

    @Tool(description = """
        检索内部知识库。调用前自动用 QueryRewrite 改写查询，
        执行混合检索（向量+BM25+RRF融合+Rerank重排序），返回 topK 条文档。
        返回 JSON 中附带 _meta 信息标识当前检索轮次，Agent 据此判断是否继续。""")
    public String searchKnowledgeBase(
        @ToolParam(description = "检索查询文本") String query,
        @ToolParam(description = "返回文档数量，默认5，最大20") Integer topK
    ) {
        RoundInfo info = guard.beforeSearch();
        int k = topK != null ? Math.min(topK, 20) : 5;
        String rewritten = queryRewriteService.rewrite(query);
        List<VectorSearchService.SearchResult> results =
            vectorSearchService.searchSimilarDocuments(rewritten, k);
        return buildResponse(query, rewritten, results, info);
    }
}
```

返回 JSON 结构：
```json
{
  "_meta": {"round": 1, "maxRounds": 3, "remainingRounds": 2},
  "query": "原始查询",
  "rewrittenQuery": "改写后查询",
  "totalResults": 5,
  "results": [{"id":"...", "content":"...", "score": 0.85, "rerankScore": 0.92}, ...]
}
```

### 4.3 EvaluateSearchResultsTool

```java
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class EvaluateSearchResultsTool {

    @Value("${dashscope.api.key}") private String apiKey;
    @Value("${rag.agentic.evaluator-model:qwen-turbo}") private String model;

    @Tool(description = """
        评估搜索结果与查询的相关性。对每条结果用轻量 LLM 评 0-1 分，
        返回评估报告供 Agent 判断是否需要改写查询重新检索。
        当 overallRelevance < 0.6 时建议 refineQuery 后重新检索。""")
    public String evaluateSearchResults(
        @ToolParam(description = "原始查询文本") String query,
        @ToolParam(description = "searchKnowledgeBase 返回的完整 JSON 结果") String resultsJson
    ) { ... }
}
```

返回 JSON 结构：
```json
{
  "overallRelevance": 0.62,
  "summary": "5条结果中3条高相关(≥0.7)，1条中等，1条不相关。建议保留前3条继续生成。",
  "evaluations": [
    {"index": 0, "relevance": 0.92, "verdict": "HIGHLY_RELEVANT", "reason": "直接描述CPU高负载处理步骤"},
    {"index": 1, "relevance": 0.78, "verdict": "RELEVANT", "reason": "包含相关监控指标"},
    {"index": 2, "relevance": 0.31, "verdict": "NOT_RELEVANT", "reason": "讲述磁盘问题而非CPU"}
  ],
  "recommendation": "PROCEED"
}
```

推荐值枚举：`PROCEED`（可以生成）| `REFINE`（建议改写重试）| `DECOMPOSE`（建议拆解问题）

### 4.4 RefineQueryTool

```java
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class RefineQueryTool {

    @Autowired private QueryRewriteService queryRewriteService;

    @Tool(description = """
        根据评估反馈改写/扩写查询文本。当 evaluateSearchResults 的
        recommendation 为 REFINE 时调用，用反馈信息生成更精确的查询。""")
    public String refineQuery(
        @ToolParam(description = "原始查询") String query,
        @ToolParam(description = "评估反馈文本，从 evaluateSearchResults 的 summary/evaluations 中提取") String feedback
    ) {
        // 将 feedback 作为附加上下文传入 QueryRewriteService
        // 如果 feedback 为空，直接用原有策略改写
        String rewritten = queryRewriteService.rewrite(query);
        return "{\"refinedQuery\": \"" + escapeJson(rewritten) + "\"}";
    }
}
```

**注意**：当前设计复用 `QueryRewriteService.rewrite()`，后续可扩展接口增加 `rewrite(String query, String feedback)` 重载以更好地利用反馈信息。

### 4.5 DecomposeQuestionTool

```java
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class DecomposeQuestionTool {

    @Value("${dashscope.api.key}") private String apiKey;
    @Value("${rag.agentic.decomposer-model:qwen-turbo}") private String model;
    @Value("${rag.agentic.max-sub-questions:5}") private int maxSubQuestions;

    @Tool(description = """
        拆解复杂问题。将对比/分析/多步/复合类问题拆成独立子问题列表。
        简单事实类问题返回 type=simple，子问题列表只含原问题。
        每个子问题可独立进行检索。""")
    public String decomposeQuestion(
        @ToolParam(description = "用户原始问题") String question
    ) { ... }
}
```

返回 JSON 结构：
```json
{
  "type": "complex",
  "complexityReason": "问题涉及两个主题的对比分析",
  "subQuestions": [
    {"index": 1, "query": "CPU高负载应急处理流程", "reason": "用户询问CPU相关处理"},
    {"index": 2, "query": "内存泄漏排查和修复步骤", "reason": "用户需要对比的第二个主题"}
  ]
}
```

简单问题返回：
```json
{
  "type": "simple",
  "complexityReason": "单主题事实性问题",
  "subQuestions": [{"index": 1, "query": "（原问题）", "reason": "无需拆解"}]
}
```

### 4.6 GetSearchCapabilitiesTool

```java
@Component
@ConditionalOnProperty(prefix = "rag.agentic", name = "enabled", havingValue = "true")
public class GetSearchCapabilitiesTool {

    @Value("${rag.top-k:3}") private int defaultTopK;

    @Tool(description = """
        获取当前知识库检索系统能力信息，包括 topK 范围、可用检索模式、
        知识库覆盖范围等。Agent 首次处理检索任务时可调用此工具了解能力边界。""")
    public String getSearchCapabilities() {
        return """
        {
          "knowledgeBase": "内部运维文档（CPU/内存/磁盘/服务可用性/响应延迟等）",
          "defaultTopK": %d,
          "maxTopK": 20,
          "searchModes": ["hybrid_dense_bm25"],
          "capabilities": ["keyword_search", "semantic_search", "rerank"],
          "queryRewriteStrategies": ["prompt_rewrite", "hypothetical_answer", "detail_abstract", "direct"]
        }""".formatted(defaultTopK);
    }
}
```

## 5. 护栏机制

### 5.1 AgenticRagProperties

```java
@Configuration
@ConfigurationProperties(prefix = "rag.agentic")
public class AgenticRagProperties {
    /** 全局开关，false 时新工具不注册，完全回退到传统 RAG */
    private boolean enabled = false;

    /** 最大检索轮次 */
    private int maxSearchRounds = 3;

    /** 最低相关性阈值（0-1），低于此值的结果视为不相关 */
    private double minRelevanceScore = 0.6;

    /** 生成答案所需的最少达标结果数 */
    private int minResultsForAnswer = 1;

    /** 降级策略：use_best（用最好的结果强制生成） */
    private String fallbackStrategy = "use_best";

    /** 整个检索阶段超时（秒） */
    private long timeoutSeconds = 60;

    /** 问题拆解时最大子问题数 */
    private int maxSubQuestions = 5;

    /** 评估结果用的轻量模型 */
    private String evaluatorModel = "qwen-turbo";

    /** 问题拆解用的模型 */
    private String decomposerModel = "qwen-turbo";
}
```

### 5.2 AgenticRagGuard

```java
@Component
public class AgenticRagGuard {

    private final AgenticRagProperties properties;
    private final ThreadLocal<Integer> roundCounter = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Long> startTime = ThreadLocal.withInitial(System::currentTimeMillis);

    /** 新一轮对话开始时重置计数器 */
    public void reset() {
        roundCounter.set(0);
        startTime.set(System.currentTimeMillis());
    }

    /** 检索前调用，返回当前轮次信息，自动递增 */
    public RoundInfo beforeSearch() {
        int current = roundCounter.get() + 1;
        roundCounter.set(current);
        return new RoundInfo(current, properties.getMaxSearchRounds());
    }

    /** 判断是否应该强制停止检索 */
    public boolean shouldForceStop() {
        if (roundCounter.get() >= properties.getMaxSearchRounds()) return true;
        if (System.currentTimeMillis() - startTime.get() > properties.getTimeoutSeconds() * 1000) return true;
        return false;
    }

    public record RoundInfo(int round, int maxRounds, int remainingRounds) {}
}
```

Guard **不拦截工具调用**（不在代码层面阻止 Agent），而是通过两种方式引导 Agent：
1. 每个检索工具的返回 JSON 中带 `_meta` 字段，告知当前轮次和剩余轮次
2. System Prompt 中明确指令：`remainingRounds == 0` 时必须停止检索并生成答案

### 5.3 超时保护

单个工具调用依赖 Spring AI 默认超时。整个检索阶段超时通过 Guard + System Prompt 实现：当 `shouldForceStop()` 为 true 时，工具返回的 `_meta.remainingRounds` 被强制设为 0，Agent 据此停止。

## 6. System Prompt 增强

在 `ChatService.buildSystemPrompt()` 中，当 `rag.agentic.enabled = true` 时追加以下指令块：

```markdown
## 知识检索策略（Agentic RAG）

你有多个知识检索工具，请按以下策略使用：

### 检索流程
1. **了解能力**：首次处理用户问题时，调用 getSearchCapabilities 了解可用检索能力
2. **判断问题类型**：
   - 简单事实类 → 直接调用 queryInternalDocs 或 searchKnowledgeBase
   - 对比/分析/多步类 → 先调用 decomposeQuestion 拆解子问题
   - 纯闲聊/无事实需求 → 直接回答，无需检索
3. **执行检索**：对每个(子)问题调用 searchKnowledgeBase，topK 默认 5
4. **评估质量**：每次检索后调用 evaluateSearchResults 判断相关性
5. **精炼重试**：当 recommendation 为 REFINE 时，调用 refineQuery 改写后重新检索

### 停止条件（满足任一即停止检索，基于已有结果生成答案）
- 有 ≥1 条结果相关性 ≥ {minRelevanceScore}
- _meta.remainingRounds == 0
- 同一 query 连续 2 次评估 recommendation 仍为 REFINE

### 生成阶段
- 综合所有达标结果生成答案，注明信息来源
- 如果确实无相关信息，如实告知用户，不要编造
- 严禁无限检索！remainingRounds 为 0 时必须基于已有最好结果强制回答
```

## 7. 配置

### 7.1 application.yml 新增配置

```yaml
rag:
  agentic:
    enabled: true                        # 全局开关：true=Agentic RAG, false=传统RAG
    max-search-rounds: 3                 # 最大检索轮次
    min-relevance-score: 0.6             # 最低相关性阈值（0-1）
    min-results-for-answer: 1            # 生成答案所需的最少达标结果数
    fallback-strategy: use_best          # 降级策略
    timeout-seconds: 60                  # 检索阶段总超时（秒）
    max-sub-questions: 5                 # 问题拆解最大子问题数
    evaluator-model: qwen-turbo          # 评估模型
    decomposer-model: qwen-turbo         # 拆解模型
```

### 7.2 ChatService 条件注入

```java
// buildSystemPrompt() 中条件追加
@Value("${rag.agentic.enabled:false}")
private boolean agenticRagEnabled;

public String buildSystemPrompt(List<Map<String, String>> history, String summary) {
    StringBuilder sb = new StringBuilder();
    // ... 现有基础提示词 ...

    if (agenticRagEnabled) {
        sb.append(AGENTIC_RAG_INSTRUCTIONS);  // 注入 Agentic RAG 行为指令
    }

    return sb.toString();
}
```

## 8. 错误处理与降级

### 8.1 工具级降级

| 工具 | 异常场景 | 降级行为 |
|------|---------|---------|
| `searchKnowledgeBase` | Milvus 不可用 / 超时 | 返回 `{"_meta":{...}, "error":"...", "results":[]}`，Agent 看到空结果 + remainingRounds=0 → 如实告知用户 |
| `evaluateSearchResults` | 评估 LLM 超时 | 跳过评估，假设所有结果相关度为 0.5，recommendation=PROCEED |
| `refineQuery` | 改写 LLM 超时 | 返回原 query，Agent 用原 query 再次检索 |
| `decomposeQuestion` | 拆解 LLM 超时 | 返回 `{"type":"simple", "subQuestions":[原问题]}`，退化为单步检索 |

### 8.2 边界情况

| 场景 | 行为 |
|------|------|
| 知识库为空 | `searchKnowledgeBase` 返回空，Agent 告知 "知识库暂无相关信息" |
| 用户问题过于宽泛 | `decomposeQuestion` 返回 simple 类型，直接检索 |
| 所有结果都不相关 | 3 轮耗尽后降级生成，Agent 如实说明 |
| Agent 完全不用 RAG 工具 | 不强制，纯对话场景允许跳过检索 |
| `agentic.enabled=false` | 新工具不注册，System Prompt 不含 Agentic RAG 指令，行为与当前完全一致 |

## 9. Agent 决策状态机

```
                ┌─────────┐
    开始 ──────→│ 判断类型 │
                └────┬────┘
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
      简单问题   复杂问题    无需检索
          │          │          │
          │          ▼          │
          │    decomposeQuestion│
          │          │          │
          │          ▼          │
          └──→ searchKnowledgeBase ←──────┐
                     │                      │
                     ▼                      │
              evaluateSearchResults         │
                     │                      │
              ┌──────┴──────┐              │
              ▼              ▼              │
          相关度 ≥ 0.6   相关度 < 0.6       │
              │              │              │
              │         remainingRounds     │
              │           > 0 ?             │
              │         ┌────┴────┐         │
              │         ▼         ▼         │
              │        Yes        No        │
              │         │         │         │
              │    refineQuery    │         │
              │         │         │         │
              │         └─────────┘         │
              │                             │
              ▼                             ▼
         generateAnswer               强制生成答案
         （基于达标结果）              （基于best-effort）
```

## 10. 测试策略

| 层级 | 内容 |
|------|------|
| 单元测试 | 每个新工具独立测试（含降级路径）；Guard 计数器逻辑；`_meta` 字段注入；条件注册开关 |
| 集成测试 | 完整 ReAct 循环：简单问题 1 轮结束；复杂问题多轮检索 → 最终生成 |
| 边界测试 | 空知识库、全部不相关、超时降级、最大轮次触发、开关关闭 |
| 回归测试 | `queryInternalDocs` 行为不变；`RagService` 不受影响；AIOps 不受影响；`agentic.enabled=false` 时完全回退 |

## 11. Agent 典型调用流示例

### 简单问题（1 轮完成）

```
用户: "CPU高负载怎么处理？"
Agent: searchKnowledgeBase("CPU高负载处理流程", topK=5)
       → 3条高相关结果
Agent: evaluateSearchResults("CPU高负载处理流程", results)
       → overallRelevance: 0.88, recommendation: PROCEED
Agent: 基于达标结果生成答案
```

### 复杂问题（多轮+拆解）

```
用户: "对比CPU高负载和内存泄漏的处理方式"
Agent: decomposeQuestion("对比CPU高负载和内存泄漏的处理方式")
       → ["CPU高负载处理流程", "内存泄漏处理流程"]

Agent: searchKnowledgeBase("CPU高负载处理流程", topK=5) → 5条结果
Agent: evaluateSearchResults → overallRelevance: 0.85, PROCEED

Agent: searchKnowledgeBase("内存泄漏处理流程", topK=5) → 3条结果
Agent: evaluateSearchResults → overallRelevance: 0.42, REFINE

Agent: refineQuery("内存泄漏处理流程", "结果偏少，需要更具体的步骤")
       → "内存泄漏排查步骤 根因定位 修复方案"

Agent: searchKnowledgeBase("内存泄漏排查步骤 根因定位 修复方案", topK=5) → 4条结果
Agent: evaluateSearchResults → overallRelevance: 0.78, PROCEED

Agent: 综合两批达标结果生成对比答案
```

## 12. 风险与缓解

| 风险 | 缓解 |
|------|------|
| Agent 陷入无限检索循环 | Guard 硬限制 maxSearchRounds=3 + 超时 60s；System Prompt 明确指令停止条件 |
| 工具过多导致 Agent 选择困难 | 保留 `queryInternalDocs` 作为简单路径；工具 description 写清使用场景 |
| 评估模型（qwen-turbo）评分不准 | 不作为硬拦截，只是建议；Agent 仍可自行判断；后续可升级模型 |
| 多轮检索增加延迟 | 简单问题走 `queryInternalDocs` 直通；用户感知延迟通过 SSE 流式逐步呈现 |
| 新增代码影响现有功能 | `agentic.enabled=false` 时完全不加载新代码；新工具复用现有 Service 而非重写 |

## 13. 迁移计划

1. **Phase 1**：新增 `AgenticRagProperties` + `AgenticRagGuard`（配置和护栏先行，零影响）
2. **Phase 2**：新增 5 个工具 + 条件注册（`agentic.enabled=false` 时不生效）
3. **Phase 3**：`ChatService` 条件注入 System Prompt 增强
4. **Phase 4**：测试 → 设 `agentic.enabled=true` 上线
5. **回退**：任何问题只需设 `agentic.enabled=false` 即可完全回退
