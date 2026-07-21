# Mem0 风格长期记忆集成设计

> 日期：2026-07-21 | 分支：待定 | 状态：待实现

## 1. 目标

在现有 Redis 短期会话存储之上，集成 Mem0 风格的长期记忆系统，实现：

1. **重要结论提取** — 从对话中自动提取用户提到的技术事实和历史决策
2. **用户画像建立** — 跨会话构建用户身份、角色、技术栈画像
3. **行为偏好学习** — 了解用户的信息呈现偏好、问题排查习惯
4. **向用户透明** — 提供「我的记忆」前端面板，用户可查看和删除记忆

**范围限定**：仅升级对话路径（`ChatController → ChatService → ReactAgent`），AIOps 路径和 RAG 文档检索保持不变。

## 2. 核心设计决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| 部署方式 | Java 原生实现 | 复用 DashScope + Milvus，不引入 Python Sidecar |
| 记忆维度 | 三者全做 | FACT（事实）/ PROFILE（画像）/ PREFERENCE（偏好） |
| 提取时机 | 会话结束 + 消息增量达标 | 新增 ≥6 对消息后异步批量提取 |
| 生命周期 | 分层 TTL | FACT 永不过期 / PROFILE 90天 / PREFERENCE 30天 |
| 冲突处理 | LLM 判断（qwen-turbo） | 判断更新/合并/并存 |
| 注入方式 | 混合（System Prompt + Agent Tool） | 画像/偏好注入提示词，事实按需 Tool 查询 |
| 用户标识 | 简单 userId 参数 | 前端传入，后续可扩展为登录系统 |
| 向量检索 | 纯向量搜索 | 不复用 BM25/Rerank，仅 L2 相似度 + userId 过滤 |
| 向量库 | 独立 Milvus collection | `user_memory`，与知识库 `biz` 隔离 |

## 3. 整体架构

```
┌─ 前端 ──────────────────────────────────────────────────────┐
│  index.html / app.js                                         │
│  + 新增："我的记忆"面板（查看/删除记忆）                       │
└──────────────────────────┬──────────────────────────────────┘
                           │ /api/chat, /api/memory/*
                           ▼
┌─ ChatController ────────────────────────────────────────────┐
│  • 从 MemoryManager 获取用户画像/偏好 → 注入 System Prompt    │
│  • 每轮对话结束后检测消息增量 → 达标则触发 MemoryExtractor    │
└──────────────────────────┬──────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
┌─ SessionManager ─┐ ┌─ MemoryManager ─┐ ┌─ MemoryExtractor ─┐
│ Redis 三层存储    │ │ 记忆 CRUD        │ │ @Async 批量提取    │
│ 短期上下文 → 不动  │ │ Milvus 读写      │ │ LLM 分析 + 抽取    │
│                   │ │ 冲突检测 + 解决   │ │ 向量化 + 冲突判断   │
└──────────────────┘ └────────┬────────┘ └────────┬──────────┘
                              │                    │
                              ▼                    ▼
                     ┌─ Milvus ───────────┐  ┌─ DashScope ───┐
                     │ collection:         │  │ qwen-turbo     │
                     │ user_memory (新建)  │  │ (提取+冲突)     │
                     │ biz (保持不变)      │  │ text-embed-v4  │
                     └────────────────────┘  │ (向量化)        │
                                             └───────────────┘
```

**核心原则：**
- **Redis** 继续管短期会话上下文 —— 不动
- **Milvus `user_memory`** 管长期用户记忆 —— 新增
- **Milvus `biz`** 管知识库文档 —— 不动
- 两条线各自独立，互不干扰

## 4. 数据模型

### 4.1 Milvus Collection: `user_memory`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VarChar(256) PK | UUID |
| `user_id` | VarChar(128) | 用户标识，建标量索引用于过滤 |
| `vector` | FloatVector(1024) | DashScope text-embedding-v4 |
| `content` | VarChar(4096) | 记忆文本 |
| `metadata` | JSON | 结构化记忆元数据 |

向量索引：`IVF_FLAT` + `L2` 距离度量，`nlist=128`，与 `biz` collection 配置一致。

### 4.2 Metadata JSON 结构

```json
{
  "type": "FACT",
  "confidence": 0.90,
  "sourceSession": "uuid-of-session",
  "sourceRound": 3,
  "createdAt": "2026-07-21T10:00:00Z",
  "updatedAt": "2026-07-21T15:00:00Z",
  "lastAccessedAt": "2026-07-21T15:00:00Z",
  "ttlSeconds": 0,
  "decayCount": 0
}
```

### 4.3 三种记忆类型与 TTL

| 类型 | TTL | 示例 |
|------|-----|------|
| `FACT` | 0（永不过期） | "用户公司 K8s 集群版本为 1.28"、"2026-07-15 CPU 告警根因是 Pod OOM" |
| `PROFILE` | 90 天 | "用户是 SRE 运维工程师，负责生产环境"、"技术栈偏向 Java/Spring Boot" |
| `PREFERENCE` | 30 天 | "用户偏好表格而非段落描述"、"排查问题时习惯先看日志再查指标" |

> **TTL 与衰减机制的关系**：TTL 是硬过期（到期强制删除），衰减是软质量过滤（置信度随时间降低）。
> 两者独立运作——即使 FACT 的 TTL=0（永不过期），如果长期未被访问，衰减机制仍会逐步降低其置信度，
> 低于阈值后也会被清理。这确保"永不过期"不代表"永远保留低质量记忆"。

### 4.4 前提变更：ChatRequest 新增 userId

`ChatRequest` 新增 `userId` 字段（可选，向后兼容）：

```java
public static class ChatRequest {
    private String id;        // sessionId
    private String question;
    private String userId;    // 新增：用户标识
}
```

前端首次请求时生成 UUID 作为 userId 并持久化到 localStorage，后续所有请求携带。

## 5. 记忆提取流程（Write Path）

### 5.1 触发条件

`SessionManager.addMessage()` 中，每次追加消息后检测：

```
newPairsSinceLastExtraction = currentPairCount - lastExtractedMessageCount
if (newPairsSinceLastExtraction >= trigger-message-count（默认 6）) {
    异步触发 MemoryExtractor
}
```

`lastExtractedMessageCount` 记录在 `SessionMeta` 中（新增字段），每次提取完成后更新。

去重机制：Redis 分布式锁 `session:{id}:memory-lock`（SETNX，60s TTL），复用现有 `SummaryGenerator` 的锁模式。

### 5.2 提取流程

```
SessionManager.addMessage() → 检测增量达标
        │
        ▼
MemoryExtractor.extract(sessionId, userId)  [@Async memoryExecutor]
        │
        ├─ 1. 读取 session 完整对话历史（Redis session:{id}:history）
        ├─ 2. 读取用户已有记忆摘要（Milvus user_memory, filter: userId）
        ├─ 3. 组装 prompt → 调用 qwen-turbo
        │     输出: { memories: [{type, content, confidence}, ...] }
        │     如果 memories 为空 → 跳过后续步骤
        │
        ├─ 4. 对每条新提取的记忆：
        │      a. DashScope text-embedding-v4 向量化
        │      b. Milvus 搜索已有记忆（同 userId + 向量相似度 > score-threshold）
        │      c. 如有冲突 → qwen-turbo 判断: UPDATE / MERGE / NEW
        │      d. 写入 Milvus：
        │         - NEW: insert 新记录
        │         - UPDATE: upsert 覆盖旧记录（content + confidence 更新）
        │         - MERGE: 更新旧记录 content 合并，delete 新记录
        │
        └─ 5. 更新 session meta（标记已提取，防止重复触发）
```

### 5.3 提取 Prompt 模板

```
你是一个记忆提取器。分析以下对话，提取关于用户的重要信息。

已有记忆：
{existing_memories}

对话历史：
{conversation_history}

请提取三类信息：
1. FACT（事实结论）：用户明确提到的技术事实、环境信息、历史决策结果
2. PROFILE（用户画像）：用户的职业角色、技能领域、职责范围
3. PREFERENCE（行为偏好）：用户表达的信息呈现偏好、工作习惯、交流风格

要求：
- 只提取明确的信息，不要推测
- 每条记忆置信度 0-1，模糊信息给低分
- 如果对话中没有值得提取的信息，返回空列表
- 输出 JSON: {"memories": [{"type": "FACT", "content": "...", "confidence": 0.9}]}
```

### 5.4 冲突判断 Prompt 模板

```
用户已有以下记忆：
旧记忆: "{old_content}" (置信度: {old_confidence})

从最新对话中提取到：
新记忆: "{new_content}" (置信度: {new_confidence})

判断新旧记忆的关系：
- UPDATE: 新信息是旧信息的更新（如版本升级），覆盖旧记忆
- MERGE: 两者可以合并为一条更完整的记忆
- NEW: 两者是不同的信息，应该各自保留

输出 JSON: {"action": "UPDATE|MERGE|NEW", "mergedContent": "仅MERGE时需要", "reason": "..."}
```

## 6. 记忆注入流程（Read Path）

### 6.1 System Prompt 注入

`ChatService.buildSystemPrompt()` 中新增记忆注入区块：

```markdown
## 用户画像

（从 Milvus 查询 userId 的 PROFILE + PREFERENCE 类型记忆，
  confidence DESC 排序，Top-N 总长度 ≤ maxLength=500 字符）

关于用户你知道：
- 用户是一名 SRE 运维工程师，负责公司生产环境
- 用户偏好使用表格呈现对比信息
- 用户排查问题时倾向于先查看日志再查看指标
```

- 仅注入 PROFILE 和 PREFERENCE，不注入 FACT
- 每次对话启动时实时查询，确保最新
- 条件注入：`memory.system-prompt.inject-profile` / `inject-preferences` 控制

### 6.2 Agent Tool 按需查询

新增 `RecallMemoryTool`：

```java
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class RecallMemoryTool {

    @Autowired private MemorySearchService memorySearchService;

    @Tool(description = """
        查询用户的历史记忆。当需要回忆用户之前提到过的技术细节、
        历史决策、具体偏好时调用此工具。返回匹配的记忆内容和置信度。""")
    public String recallMemory(
        @ToolParam(description = "搜索查询文本") String query,
        @ToolParam(description = "返回数量，默认3，最大10") Integer topK
    ) {
        // 纯向量搜索：query → embedding → Milvus search(userId filter) → 返回结果
        // 同时更新 lastAccessedAt
    }
}
```

返回 JSON：

```json
{
  "query": "CPU告警根因",
  "results": [
    {
      "id": "mem-001",
      "type": "FACT",
      "content": "2026-07-15 CPU告警根因是Pod OOM，内存限制从512Mi调整到2Gi",
      "confidence": 0.85,
      "score": 0.92
    }
  ]
}
```

## 7. 记忆管理

### 7.1 置信度衰减

`MemoryDecayService` 通过 `@Scheduled` 定时执行：

```
每天凌晨 3 点执行
    │
    ├─ 查询所有记忆（Milvus query iterator）
    ├─ 对每条记忆：
    │     if now - lastAccessedAt > noAccessThreshold（7天）:
    │         confidence -= decayFactor（0.1）
    │         decayCount++
    │         if confidence < minConfidence（0.3）→ 删除
    │         else → 更新 Milvus metadata
    └─ 记录衰减日志
```

### 7.2 手动删除

新增 `ForgetMemoryTool`，Agent 可按用户指令删除：

```java
@Component
@ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")
public class ForgetMemoryTool {

    @Autowired private MemoryManager memoryManager;

    @Tool(description = """
        删除用户的记忆。当用户明确要求"忘记"某些信息时调用。
        支持按记忆ID精确删除或按关键词搜索后删除。""")
    public String forgetMemory(
        @ToolParam(description = "记忆ID，或用于搜索记忆的关键词") String target,
        @ToolParam(description = "当前用户ID，从会话上下文获取") String userId
    ) {
        // 先搜索匹配的记忆，确认后删除
    }
}
```

### 7.3 REST API

```java
// MemoryController.java
GET    /api/memory/panel?userId=xxx        → 获取所有记忆（按类型分组：facts / profiles / preferences）
DELETE /api/memory/{memoryId}?userId=xxx   → 删除单条记忆
DELETE /api/memory/clear?userId=xxx        → 清空用户全部记忆
```

### 7.4 前端「我的记忆」面板

侧边栏新增「🧠 我的记忆」入口，展开三 Tab 面板：

| Tab | 内容 | 操作 |
|-----|------|------|
| 📌 事实结论 | 提取的技术事实，含置信度进度条 + 来源会话 | 删除（二次确认） |
| 👤 用户画像 | 用户身份/角色信息 | 删除（二次确认） |
| 🎯 行为偏好 | 行为偏好记录 | 删除（二次确认） |

特性：
- 顶部统计卡片显示各类型记忆数量
- 置信度进度条颜色编码：绿色(≥0.7) / 黄色(0.4-0.7) / 红色(<0.4)
- 衰减中记忆黄色边框高亮 + "N天未访问"标签
- 空 Tab 显示占位提示
- 具体 UI 风格由前端 skill 重写实现

## 8. 配置文件

### 8.1 application.yml 新增配置

```yaml
memory:
  enabled: true                          # 全局开关：false 时 MemoryManager/提取/注入全部停用
  extraction:
    trigger-message-count: 6             # 会话新增消息对超过此数触发提取
    model: qwen-turbo                    # 提取 + 冲突判断用的轻量 LLM
    max-batch-messages: 50               # 一次提取最多分析的对话条数
  search:
    top-k: 5                             # recallMemory 默认返回数
    score-threshold: 0.6                 # 冲突检测时向量相似度最低阈值
  decay:
    enabled: true
    cron: "0 3 * * *"                    # 每天凌晨 3 点执行
    decay-factor: 0.1                    # 每次衰减的置信度减少量
    min-confidence: 0.3                  # 低于此值自动删除
    no-access-threshold-hours: 168       # 7 天无访问触发衰减
  ttl:
    fact-hours: 0                        # 0 表示永不过期
    profile-hours: 2160                  # 90 天（90 × 24）
    preference-hours: 720                # 30 天（30 × 24）
  system-prompt:
    inject-profile: true                 # 是否注入用户画像到 System Prompt
    inject-preferences: true             # 是否注入行为偏好到 System Prompt
    max-length: 500                      # 注入内容最大字符数
```

## 9. 线程池规划

| 线程池名称 | 用途 | core | max | queue | 拒绝策略 |
|-----------|------|------|-----|-------|---------|
| `summaryExecutor` | 现有：会话摘要生成 | 1 | 2 | 100 | CallerRunsPolicy |
| `searchExecutor` | 现有：混合检索并行 | 2 | 4 | 10 | CallerRunsPolicy |
| `memoryExecutor`（新增） | 记忆提取 + 冲突判断 | 1 | 2 | 50 | CallerRunsPolicy |

## 10. 文件变更清单

### 10.1 新增文件

```
src/main/java/org/example/
├── config/
│   └── MemoryProperties.java           ← @ConfigurationProperties("memory")
├── service/
│   ├── MemoryManager.java              ← 记忆 CRUD（Milvus 协调读写）
│   ├── MemoryExtractor.java            ← @Async 批量提取记忆
│   ├── MemorySearchService.java        ← 纯向量搜索（userId 过滤）
│   └── MemoryDecayService.java         ← @Scheduled 定时衰减
├── agent/tool/
│   ├── RecallMemoryTool.java           ← @Tool("recallMemory") 按需查询
│   └── ForgetMemoryTool.java           ← @Tool("forgetMemory") 按指令删除
└── controller/
    └── MemoryController.java           ← REST: /api/memory/*
```

### 10.2 修改文件

| 文件 | 变更内容 |
|------|---------|
| `ChatService.java` | `buildSystemPrompt()` 追加用户画像/偏好区块（条件注入）；`buildMethodToolsArray()` 追加 RecallMemoryTool / ForgetMemoryTool |
| `ChatController.java` | `ChatRequest` 新增 `userId` 字段；`chat()`/`chatStream()` 传递 userId |
| `SessionManager.java` | `addMessage()` 后检测消息增量，达标则调用 MemoryExtractor |
| `application.yml` | 新增 `memory.*` 配置块 |
| `MilvusClientFactory.java` | 新建 `user_memory` collection（与 `biz` 创建逻辑并行） |
| `MilvusConstants.java` | 新增 `MEMORY_COLLECTION_NAME = "user_memory"`、`MEMORY_VECTOR_DIM = 1024` 常量 |
| `AsyncConfig.java` | 新增 `memoryExecutor` Bean |

### 10.3 不改的文件

`RagService`、`VectorSearchService`、`VectorIndexService`、`VectorEmbeddingService`、`SummaryGenerator`、`AiOpsService`、`RedisConfig`、`QueryRewriteService`、所有现有工具类 —— 全部不动。

### 10.4 前端

| 文件 | 变更 |
|------|------|
| `index.html` | 侧边栏新增「🧠 我的记忆」入口 |
| `memory-panel.html` | 新建：记忆面板页面 |
| `app.js` | 新增记忆面板数据获取、Tab 切换、删除确认、userId 持久化 |
| `styles.css` | 新增记忆面板样式 |

## 11. 错误处理与降级

| 场景 | 降级行为 |
|------|---------|
| Milvus 不可用 | MemoryManager 操作返回空/失败，不影响对话主流程 |
| 记忆提取 LLM 超时 | 静默跳过本次提取，下次对话时重试 |
| 冲突判断 LLM 超时 | 默认 action=NEW，两条都保留，后续由衰减机制清理 |
| 衰减任务执行失败 | 记录错误日志，下次 cron 周期自动重试 |
| `memory.enabled=false` | MemoryManager/MemoryExtractor/RecallMemoryTool/ForgetMemoryTool 全部不注册，System Prompt 不注入记忆区块，完全回退到当前行为 |
| 前端未传 userId | 降级为当前 session 级别行为（与传统模式一致），长期记忆不生效 |

## 12. Agent 典型调用流

```
用户: "上次那个CPU告警最后怎么解决的？"

[System Prompt 注入]
关于用户你知道：
- 用户是 SRE 运维工程师，负责公司生产环境
- 用户偏好表格而非段落描述

Agent 思考: 用户问的是历史事实 → 调用 recallMemory("CPU告警 根因 解决方案")
    → 返回: "2026-07-15 CPU告警根因是Pod OOM，内存限制从512Mi调整为2Gi"

Agent 回答: "根据7月15日的记录，CPU告警根因是 api-gateway Pod 内存限制过低
           (512Mi) 导致 OOM 重启。当时将限制上调到 2Gi 后问题解决。"

[本轮结束后]
    → SessionManager 检测新增消息对 ≥ 6
    → 异步触发 MemoryExtractor
    → 分析发现无新事实 → 跳过写入
```

## 13. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 提取质量不稳定 | 使用轻量模型做初筛，低置信度记忆(<0.5)不写入；后续可调整 prompt |
| 记忆膨胀 | 衰减机制自动清理低置信度/长期未访问记忆；分层 TTL 控制总量 |
| Milvus 内存占用增加 | `user_memory` 数据量远小于 `biz`（每个用户数十条 vs 文档数千条） |
| 提取 LLM 消耗 | qwen-turbo 成本极低，仅会话达标时触发，非每轮调用 |
| 用户隐私顾虑 | 前端面板透明展示所有记忆，用户可随时删除；提供 /api/memory/clear 一键清空 |
| 新增代码影响现有功能 | `memory.enabled=false` 时完全不加载新代码；所有新工具条件注册 |

## 14. 实现阶段

1. **Phase 1**：`MemoryProperties` + `MilvusClientFactory` 建 collection + `MemoryDecayService`（配置和基础设施先行，零影响）
2. **Phase 2**：`MemoryManager` + `MemorySearchService` + `RecallMemoryTool` + `ForgetMemoryTool`（记忆 CRUD + Agent 工具）
3. **Phase 3**：`MemoryExtractor` + `SessionManager` 改造 + `ChatService` System Prompt 增强（提取和注入打通）
4. **Phase 4**：`MemoryController` + 前端「我的记忆」面板
5. **Phase 5**：测试 → 设 `memory.enabled=true` 上线
6. **回退**：任何问题只需设 `memory.enabled=false` 即可完全回退
