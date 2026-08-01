# 配置与环境管理 / Prompt 管理 / API 版本化 — 需求与设计文档

> 版本：v1 | 日期：2026-08-01 | 基于改进计划 P2-10/P2-11/P2-12

---

## 目录

1. [概述](#概述)
2. [模块10：配置与环境管理](#模块10配置与环境管理)
3. [模块11：Prompt 管理](#模块11prompt-管理)
4. [模块12：API 版本化](#模块12api-版本化)
5. [实施顺序与依赖](#实施顺序与依赖)

---

## 概述

本文档覆盖 [2026-07-22 改进计划](../../../session/idea/2026-07-22-superbizagent-improvement-plan.md) 中三个 P2 模块的详细需求与设计方案。三个模块按依赖关系排序：配置管理（基础层）→ Prompt 管理（依赖配置）→ API 版本化（独立实施）。

### 设计决策速览

| 模块 | 关键决策 |
|------|----------|
| 配置管理 | 分层模型配置 (C) · 启动时 JSR-303 校验 (A) · 特性开关文档 + 启动依赖检查 (B) |
| Prompt 管理 | Mustache 双语模板 (C) · 仅启动时加载 (A) · 约定优于配置 + 可选覆盖 (C) |
| API 版本化 | URL 路径版本化 (A) · OpenAPI 注解含示例 (B) · 旧路径 301 重定向 (B) |

---

## 模块10：配置与环境管理

### 10.1 现状分析

**已完成：**
- `application.yml` / `application-dev.yml` / `application-prod.yml` Profile 分离已存在
- 多个 `@ConfigurationProperties` 类：`MemoryProperties`、`AganticRagProperties`、`SessionRedisProperties`、`QueryRewriteProperties`、`ApiKeyProperties`、`ChunkStrategyProperties`
- 特性开关通过 `@ConditionalOnProperty` 控制 Bean 注册

**待改进：**
- 模型名称部分硬编码：`ReactAgentRunner.buildChatModel()` 使用 SDK 常量 `DashScopeChatModel.DEFAULT_MODEL_NAME`；`MemoryExtractor` 两处硬编码 `"qwen-turbo"` 字符串
- `@Value` 散落在多处（29 个文件），缺少统一的 `@ConfigurationProperties` 管理
- 两套 API Key 注入路径不一致：`spring.ai.dashscope.api-key` vs `dashscope.api.key`
- 无配置验证 —— 配错模型名只在 LLM 调用失败时才发现
- 特性开关缺少文档和依赖校验

### 10.2 需求

#### 功能需求

**FR-10-1: 模型配置分层化**
为每个任务场景提供独立的模型配置（名称 + temperature + maxToken + topP），消除代码中的硬编码。

**FR-10-2: 启动时配置验证**
应用启动时校验必填配置项和参数值范围，配错立即启动失败。

**FR-10-3: 特性开关文档与依赖检查**
维护 `docs/feature-flags.md`，启动时校验开关的前置依赖是否满足。

**FR-10-4: API Key 配置路径统一**
统一两套 API Key 注入路径，通过 `@ConfigurationProperties` 集中管理。

#### 非功能需求

- 配置变更后需重启生效（不要求运行时热更新）
- `@ConfigurationProperties` 类需生成 `spring-configuration-metadata.json`（IDE 自动提示）
- 文档中的默认值必须与代码中的默认值一致

### 10.3 设计

#### 10.3.1 模型配置分层化

新建 `ModelProperties`（prefix: `ai.model`），为每个任务场景定义独立配置：

```yaml
ai:
  model:
    chat:
      name: qwen3-max
      temperature: 0.7
      max-token: 2000
      top-p: 0.9
    aiops:
      supervisor:
        name: qwen3-max
        temperature: 0.3
        max-token: 8000
        top-p: 0.9
      planner:
        name: qwen3-max
        temperature: 0.3
        max-token: 8000
        top-p: 0.9
      executor:
        name: qwen-turbo
        temperature: 0.3
        max-token: 4000
        top-p: 0.9
    lightweight:
      name: qwen-turbo
      temperature: 0.3
      max-token: 2000
      top-p: 0.9
    reasoning:
      name: qwen3-max
      temperature: 0.3
      max-token: 4000
      top-p: 0.9
    rewrite:
      name: qwen-turbo
      temperature: 0.3
      max-token: 500
      top-p: 0.9
```

对应 Java 配置类结构：

```
ModelProperties (@ConfigurationProperties prefix="ai.model")
├── ModelConfig chat
├── AiopsModels aiops
│   ├── ModelConfig supervisor
│   ├── ModelConfig planner
│   └── ModelConfig executor
├── ModelConfig lightweight
├── ModelConfig reasoning
└── ModelConfig rewrite

ModelConfig record:
  - String name        (@NotBlank)
  - double temperature (@DecimalMin("0.0") @DecimalMax("2.0"))
  - int maxToken       (@Min(1) @Max(32768))
  - double topP        (@DecimalMin("0.0") @DecimalMax("1.0"))
```

**代码修改范围：**

| 调用点 | 修改内容 |
|--------|----------|
| `ReactAgentRunner.buildChatModel()` | `DashScopeChatModel.DEFAULT_MODEL_NAME` → `modelProperties.getChat().name()` |
| `ReactAgentRunner.buildPlannerAgent()` | → `modelProperties.getAiops().planner()` |
| `ReactAgentRunner.buildExecutorAgent()` | → `modelProperties.getAiops().executor()` |
| `ReactAgentRunner.buildSupervisorAgent()` | → `modelProperties.getAiops().supervisor()` |
| `SummaryGenerator.generateSummary()` | → `modelProperties.getLightweight()` |
| `MemoryExtractor.callLlm()` | 硬编码 `"qwen-turbo"` → `modelProperties.getLightweight().name()` |
| `MemoryExtractor.resolveConflict()` | 同上（当前硬编码字符串） |
| `MemoryExtractor.resolveMerge()` | 同上 |
| `QueryRewriteService.createRewriteChatModel()` | → `modelProperties.getRewrite()` |
| `DashScopeLlmProvider.buildModel()` | 保持通用，由调用方传入 options |

#### 10.3.2 启动时配置验证

在 `ModelProperties` 类上加 `@Validated` 注解，各字段使用 JSR-303 约束：

- `@NotBlank` — 模型名称不能为空
- `@DecimalMin` / `@DecimalMax` — temperature 范围 0.0~2.0
- `@Min` / `@Max` — maxToken 范围 1~32768
- `@DecimalMin` / `@DecimalMax` — topP 范围 0.0~1.0

Spring Boot 在绑定 `@ConfigurationProperties` 时自动执行校验，校验失败抛出 `BindException`，应用启动失败。

#### 10.3.3 特性开关文档与依赖检查

**文档：** 维护 `docs/feature-flags.md`：

```markdown
# 特性开关

| 开关 | 默认值 | 依赖条件 | 说明 |
|------|--------|----------|------|
| `memory.enabled` | true | Redis 可用 | 长短期记忆系统 |
| `rag.agentic.enabled` | false | biz collection 有数据 | Agentic RAG 多轮搜索 |
| `rag.hybrid.enabled` | true | - | BM25+向量双路召回 |
| `rag.rerank.enabled` | true | - | DashScope Rerank 重排序 |
| `rag.rewrite.cache.enabled` | true | Redis 可用 | 查询改写结果缓存 |
| `cls.mock-enabled` | false | - | CLS 日志模拟 vs MCP 真实 |
| `prometheus.mock-enabled` | false | - | Prometheus 模拟 vs 真实 API |
| `superbiz.security.enabled` | false(dev)/true(prod) | - | API Key 认证 |
| `superbiz.rate-limit.enabled` | false(dev)/true(prod) | 需 security.enabled=true | 请求限流 |
| `intent.router.enabled` | true | - | 意图识别路由 |
| `session.redis.summary.enabled` | true | - | 对话摘要生成 |
```

**启动校验：** 通过 `ApplicationReadyEvent` 监听器实现：

```java
@Component
public class FeatureFlagStartupChecker {
    
    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        // rag.agentic.enabled=true 但 biz collection 为空 → WARN
        // memory.enabled=true 但 Redis 不可用 → WARN
        // rate-limit.enabled=true 且 security.enabled=false → WARN
        // 仅打 WARN 日志，不阻止启动
    }
}
```

#### 10.3.4 API Key 配置路径统一

当前两套路径：
- `spring.ai.dashscope.api-key` — Spring AI 自动配置使用
- `dashscope.api.key` — `QueryRewriteService`、`DashScopeLlmClient` 手动注入使用

统一方案：两个路径都保留（Spring AI 框架要求 `spring.ai.dashscope.*` 路径），但业务代码全部通过 `DashScopeApiProperties`（新增）注入，集中验证。

### 10.4 涉及文件

| 文件 | 操作 |
|------|------|
| `config/ModelProperties.java` | **新增** |
| `config/DashScopeApiProperties.java` | **新增** |
| `config/FeatureFlagStartupChecker.java` | **新增** |
| `agent/ReactAgentRunner.java` | 修改：模型参数改用 ModelProperties |
| `agent/DashScopeLlmProvider.java` | 修改：统一 API Key 注入 |
| `service/SummaryGenerator.java` | 修改：模型参数改用 ModelProperties |
| `service/MemoryExtractor.java` | 修改：修复 2 处硬编码 + 改用 ModelProperties |
| `service/rewrite/QueryRewriteService.java` | 修改：模型参数改用 ModelProperties |
| `resources/application.yml` | 修改：新增 ai.model 配置段 |
| `resources/application-dev.yml` | 修改：dev 环境覆盖（可选） |
| `resources/application-prod.yml` | 修改：prod 环境覆盖 |
| `docs/feature-flags.md` | **新增** |

---

## 模块11：Prompt 管理

### 11.1 现状分析

**所有 Prompt 均内联在 Java 代码中，无外部化。**

内联 Prompt 清单（共 17 处）：

| 位置 | Prompt | 行数 |
|------|--------|------|
| `ChatService.buildSystemPrompt()` | 基础系统提示词 | ~5 |
| `ChatService.buildAgenticRagInstructions()` | Agentic RAG 指令块 | ~25 |
| `ReactAgentRunner.buildPlannerPrompt()` | Planner Agent（含报告模板） | ~90 |
| `ReactAgentRunner.buildExecutorPrompt()` | Executor Agent | ~16 |
| `ReactAgentRunner.buildSupervisorSystemPrompt()` | Supervisor Agent | ~13 |
| `ReactAgentRunner.generateFallbackReport()` | 超时兜底报告模板 | ~22 |
| `AiOpsService.executeAiOpsAnalysis()` | AIOps 任务描述（与 ReactAgentRunner 重复） | ~1 |
| `MemoryExtractor.buildExtractionPrompt()` | 记忆提取 Prompt | ~18 |
| `MemoryExtractor.resolveConflict()` | 冲突判断 Prompt | ~16 |
| `MemoryExtractor.resolveMerge()` | 合并 Prompt | ~7 |
| `SummaryGenerator.buildSummaryPrompt()` | 对话摘要 Prompt | ~8 |
| `PromptRewriteStrategy` | 查询改写 Prompt | ~10 |
| `HypotheticalAnswerStrategy` | 假想答案 Prompt | ~10 |
| `DetailAbstractStrategy` | 细节抽象 Prompt | ~15 |
| `IntentRouter.buildClassificationPrompt()` | 意图分类 Prompt | ~14 |
| `AIOpsEvaluator.buildJudgePrompt()` | 评估 Prompt | ~35 |
| `DecomposeQuestionTool` / `EvaluateSearchResultsTool` / `GetSearchCapabilitiesTool` | Agentic RAG 工具 Prompt | ~40 |

**已知问题：**
- `AiOpsService` 和 `ReactAgentRunner` 中存在完全相同的 AIOps 任务 Prompt
- `MemoryExtractor` 中系统 Prompt 硬编码为中文字符串（如 `"你是一个记忆提取器。"`）
- Prompt 修改需要重新编译部署，无法追踪变更历史

### 11.2 需求

#### 功能需求

**FR-11-1: Prompt 外部化**
所有 Prompt 迁移到 `src/main/resources/prompts/` 目录下的 `.md` 文件，启动时加载到内存。

**FR-11-2: YAML Frontmatter 元数据**
每个 Prompt 文件头部包含 YAML frontmatter，记录 version、modified、author、changes、model。

**FR-11-3: 模板变量支持**
使用 Mustache 模板引擎，支持 `{{variable}}` 变量替换和 `{{#flag}}...{{/flag}}` 条件渲染。

**FR-11-4: 中英文双语支持**
按 `prompts/zh/` 和 `prompts/en/` 分目录，运行时根据请求语言自动选择。

**FR-11-5: 约定优于配置**
默认按 `{lang}/{category}/{name}.md` 路径加载，支持 YAML 中显式覆盖路径。

**FR-11-6: 消除 Prompt 重复**
`AiOpsService` 中的重复 task prompt 合并为单一来源。

#### 非功能需求

- 仅启动时加载，不要求热重载
- 渲染失败时抛出明确异常，不静默降级为空 Prompt
- Mustache 渲染失败时保留原始模板，便于调试

### 11.3 设计

#### 11.3.1 目录结构

```
src/main/resources/prompts/
├── zh/
│   ├── chat/
│   │   ├── system-prompt.md
│   │   └── agentic-rag-instructions.md
│   ├── aiops/
│   │   ├── supervisor-prompt.md
│   │   ├── planner-prompt.md
│   │   ├── executor-prompt.md
│   │   ├── task-prompt.md
│   │   └── fallback-report.md
│   ├── memory/
│   │   ├── extraction-prompt.md
│   │   ├── conflict-resolution.md
│   │   └── merge-prompt.md
│   ├── summary/
│   │   └── summary-prompt.md
│   ├── rewrite/
│   │   ├── prompt-rewrite.md
│   │   ├── hypothetical-answer.md
│   │   └── detail-abstract.md
│   ├── intent/
│   │   └── classification-prompt.md
│   ├── eval/
│   │   └── judge-prompt.md
│   └── agentic-rag/
│       ├── decompose-question.md
│       ├── evaluate-results.md
│       └── search-capabilities.md
└── en/
    └── ...（与 zh/ 相同的目录和文件结构）
```

#### 11.3.2 Prompt 文件格式

每个 `.md` 文件使用 YAML frontmatter + 模板正文：

```markdown
---
version: 1
modified: 2026-08-01
author: chief
changes: "初始版本：从 ReactAgentRunner 提取 Planner Agent 系统提示词"
model: aiops.planner
---

你是 Planner Agent，同时承担 Replanner 角色，负责：
1. 读取当前输入任务 {{input}} 以及 Executor 的最近反馈 {{executor_feedback}}。

{{#agenticRagEnabled}}
## 增强检索策略
当前已启用 Agentic RAG，你可以使用多轮检索工具。
{{/agenticRagEnabled}}

## 最终报告输出要求（CRITICAL）

...（报告模板）
```

**Metadata 字段定义：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `version` | int | 是 | 递增版本号 |
| `modified` | date | 是 | 最后修改日期 (YYYY-MM-DD) |
| `author` | string | 是 | 修改人 |
| `changes` | string | 是 | 本次变更说明 |
| `model` | string | 否 | 关联的模型配置 key（如 `aiops.planner`），仅文档用途 |

#### 11.3.3 PromptManager 设计

```java
@Component
public class PromptManager {

    /**
     * 渲染并返回 Prompt 文本。
     *
     * @param key       Prompt 标识，如 "chat/system-prompt"
     * @param variables 模板变量 Map
     * @param lang      语言代码，如 "zh" / "en"
     * @return 渲染后的完整 Prompt 文本
     */
    public String render(String key, Map<String, Object> variables, String lang);

    /**
     * 获取 Prompt 元数据（用于运维查询）。
     */
    public PromptMeta getMeta(String key, String lang);
}
```

**加载流程：**

1. 启动时扫描 `prompts/zh/` 和 `prompts/en/` 下所有 `.md` 文件
2. 解析 frontmatter 和模板正文，编译 Mustache 模板
3. 存入内存 `Map<String, CompiledPrompt>`（key: `"zh:chat/system-prompt"`）
4. 如果 YAML 中配置了 `prompts.mappings.xxx`，覆盖默认路径

**语言选择逻辑：**

1. 如果调用时传入的 `lang` 为 `null` 或空字符串 → 使用 `prompts.default-lang`（默认 `zh`）
2. 如果 `lang` 对应的目录不存在该 Prompt → 回退到 `prompts.default-lang`
3. 如果默认语言也没有 → 抛出 `IllegalStateException("Prompt not found: key=chat/system-prompt, lang=zh")`

**异常处理策略：**

- Prompt 未找到 → `IllegalStateException`，应用无法启动（`@PostConstruct` 阶段校验全部 key 存在）
- 模板变量缺失 → Mustache 默认将缺失变量渲染为空字符串（不抛异常），符合"部分变量可选"的场景
- Mustache 语法错误 → `IllegalStateException`，应用无法启动（启动时预编译所有模板，语法错误立即暴露）

**YAML 覆盖配置（可选）：**

```yaml
prompts:
  default-lang: zh
  mappings:
    chat-system: "prompts/zh/chat/custom-system-prompt.md"  # 覆盖默认路径
```

#### 11.3.4 代码修改

**ChatService：**
```java
// 旧：手动拼接字符串
systemPromptBuilder.append("你是一个专业的智能助手...");
systemPromptBuilder.append(buildAgenticRagInstructions());

// 新：通过 PromptManager 渲染
Map<String, Object> vars = Map.of("agenticRagEnabled", agenticRagEnabled);
String systemPrompt = promptManager.render("chat/system-prompt", vars, userLang);
```

**ReactAgentRunner：**
```java
// 旧：内联 90 行 Prompt 字符串
private String buildPlannerPrompt() { return """..."""; }

// 新：从 PromptManager 加载
String plannerPrompt = promptManager.render("aiops/planner-prompt", vars, "zh");
```

**修复 Prompt 重复：** `AiOpsService.executeAiOpsAnalysis()` 中的 task prompt 改为调用 `promptManager.render("aiops/task-prompt", Map.of(), "zh")`，与 `ReactAgentRunner` 共用同一份模板。

#### 11.3.5 Prompt Key 映射表

| Prompt Key | 源文件 | 原代码位置 |
|------------|--------|-----------|
| `chat/system-prompt` | `zh/chat/system-prompt.md` | `ChatService.buildSystemPrompt()` |
| `chat/agentic-rag-instructions` | `zh/chat/agentic-rag-instructions.md` | `ChatService.buildAgenticRagInstructions()` |
| `aiops/supervisor-prompt` | `zh/aiops/supervisor-prompt.md` | `ReactAgentRunner.buildSupervisorSystemPrompt()` |
| `aiops/planner-prompt` | `zh/aiops/planner-prompt.md` | `ReactAgentRunner.buildPlannerPrompt()` |
| `aiops/executor-prompt` | `zh/aiops/executor-prompt.md` | `ReactAgentRunner.buildExecutorPrompt()` |
| `aiops/task-prompt` | `zh/aiops/task-prompt.md` | `AiOpsService.executeAiOpsAnalysis()` + `ReactAgentRunner.executeOrchestration()` |
| `aiops/fallback-report` | `zh/aiops/fallback-report.md` | `ReactAgentRunner.generateFallbackReport()` |
| `memory/extraction-prompt` | `zh/memory/extraction-prompt.md` | `MemoryExtractor.buildExtractionPrompt()` |
| `memory/conflict-resolution` | `zh/memory/conflict-resolution.md` | `MemoryExtractor.resolveConflict()` |
| `memory/merge-prompt` | `zh/memory/merge-prompt.md` | `MemoryExtractor.resolveMerge()` |
| `summary/summary-prompt` | `zh/summary/summary-prompt.md` | `SummaryGenerator.buildSummaryPrompt()` |
| `rewrite/prompt-rewrite` | `zh/rewrite/prompt-rewrite.md` | `PromptRewriteStrategy` |
| `rewrite/hypothetical-answer` | `zh/rewrite/hypothetical-answer.md` | `HypotheticalAnswerStrategy` |
| `rewrite/detail-abstract` | `zh/rewrite/detail-abstract.md` | `DetailAbstractStrategy` |
| `intent/classification-prompt` | `zh/intent/classification-prompt.md` | `IntentRouter.buildClassificationPrompt()` |
| `eval/judge-prompt` | `zh/eval/judge-prompt.md` | `AIOpsEvaluator.buildJudgePrompt()` |
| `agentic-rag/decompose-question` | `zh/agentic-rag/decompose-question.md` | `DecomposeQuestionTool` |
| `agentic-rag/evaluate-results` | `zh/agentic-rag/evaluate-results.md` | `EvaluateSearchResultsTool` |
| `agentic-rag/search-capabilities` | `zh/agentic-rag/search-capabilities.md` | `GetSearchCapabilitiesTool` |

### 11.4 涉及文件

| 文件 | 操作 |
|------|------|
| `config/PromptProperties.java` | **新增** |
| `service/PromptManager.java` | **新增** |
| `resources/prompts/zh/**/*.md` | **新增**（19 个 Prompt 文件） |
| `resources/prompts/en/**/*.md` | **新增**（19 个英文 Prompt 文件） |
| `service/ChatService.java` | 修改：buildSystemPrompt 改用 PromptManager |
| `agent/ReactAgentRunner.java` | 修改：移除内联 Prompt，改用 PromptManager |
| `service/AiOpsService.java` | 修改：消除重复 task prompt |
| `service/MemoryExtractor.java` | 修改：移除内联 Prompt，改用 PromptManager |
| `service/SummaryGenerator.java` | 修改：移除内联 Prompt，改用 PromptManager |
| `service/rewrite/PromptRewriteStrategy.java` | 修改：移除内联 Prompt，改用 PromptManager |
| `service/rewrite/HypotheticalAnswerStrategy.java` | 修改：移除内联 Prompt |
| `service/rewrite/DetailAbstractStrategy.java` | 修改：移除内联 Prompt |
| `agent/router/IntentRouter.java` | 修改：移除内联 Prompt |
| `agent/eval/AIOpsEvaluator.java` | 修改：移除内联 Prompt |
| `agent/tool/DecomposeQuestionTool.java` | 修改：移除内联 Prompt |
| `agent/tool/EvaluateSearchResultsTool.java` | 修改：移除内联 Prompt |
| `agent/tool/GetSearchCapabilitiesTool.java` | 修改：移除内联 Prompt |
| `pom.xml` | 新增 Mustache 依赖 (compile) |
| `resources/application.yml` | 新增 prompts 配置段 |

---

## 模块12：API 版本化

### 12.1 现状分析

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 非流式对话 |
| `/api/chat_stream` | POST | SSE 流式对话 |
| `/api/chat/clear` | POST | 清空会话 |
| `/api/chat/session/{sessionId}` | GET | 查询会话信息 |
| `/api/ai_ops` | POST | SSE 流式 AIOps 分析 |
| `/api/login` | POST | API Key 登录 |
| `/api/memory/panel` | GET | 记忆面板 |
| `/api/memory/{memoryId}` | DELETE | 删除单条记忆 |
| `/api/memory/clear` | DELETE | 清空全部记忆 |
| `/api/upload` | POST | 文件上传 |
| `/api/upload/reindex-failed` | POST | 重新索引失败文档 |
| `/milvus/health` | GET | Milvus 健康检查 |

**已知问题：**
- 无版本前缀，未来 API 变更无法向后兼容
- 无 Swagger/OpenAPI 文档，接口依赖代码阅读
- DTO 部分为 Controller 内嵌类（`ChatRequest`、`ClearRequest` 等），无法跨 Controller 复用
- SSE 协议无文档规范
- `ChatController` 职责过重（对话 + AIOps + 会话管理）
- `FileUploadController` 无类级别的 `@RequestMapping`，与其他 Controller 不一致
- `MilvusCheckController` 使用 `/milvus` 而非 `/api` 前缀，不统一

### 12.2 需求

#### 功能需求

**FR-12-1: URL 路径版本化**
所有 API 端点迁移到 `/api/v1` 前缀下，旧路径返回 301 永久重定向。

**FR-12-2: 引入 SpringDoc OpenAPI**
添加 `springdoc-openapi` 依赖，自动生成 Swagger UI（`/api/v1/docs/ui`）。

**FR-12-3: 注解补充**
所有 Controller 加 `@Tag`，所有端点加 `@Operation`，所有 DTO 加 `@Schema`，标注请求/响应示例和错误码。

**FR-12-4: SSE 协议文档化**
编写 `docs/api-sse-protocol.md`，标准化 SSE 事件类型和数据格式。

**FR-12-5: DTO 独立化**
Controller 内嵌 DTO 类迁移到 `dto/` 包下成为独立公共类。

**FR-12-6: Controller 职责拆分**
将 `ChatController` 拆分为 Chat、AIOps、Memory、Auth、Upload、Health 六个独立 Controller。

#### 非功能需求

- 旧路径 301 重定向完全透明，前端无需感知（浏览器自动跟随）
- Swagger UI 仅在 dev profile 可访问
- API JSON Schema 文件（`/api/v1/docs/json`）供 CI/CD 消费

### 12.3 设计

#### 12.3.1 URL 版本化与重定向

**新 Controller 结构：**

```
controller/
├── v1/
│   ├── ChatV1Controller.java       @RequestMapping("/api/v1")
│   ├── AIOpsV1Controller.java      @RequestMapping("/api/v1")
│   ├── MemoryV1Controller.java     @RequestMapping("/api/v1")
│   ├── AuthV1Controller.java       @RequestMapping("/api/v1")
│   ├── UploadV1Controller.java     @RequestMapping("/api/v1")
│   └── HealthV1Controller.java     @RequestMapping("/api/v1")
├── legacy/
│   ├── ChatLegacyController.java   @RequestMapping("/api")    → 301 重定向
│   ├── AIOpsLegacyController.java  @RequestMapping("/api")    → 301 重定向
│   ├── MemoryLegacyController.java @RequestMapping("/api")    → 301 重定向
│   ├── AuthLegacyController.java   @RequestMapping("/api")    → 301 重定向
│   ├── UploadLegacyController.java @RequestMapping("/api")    → 301 重定向
│   └── MilvusLegacyController.java @RequestMapping("/milvus") → 301 重定向
```

301 重定向实现示例（Legacy Controller —— 不解析请求体，直接返回重定向）：

```java
@RestController
@RequestMapping("/api")
public class ChatLegacyController {
    
    @PostMapping("/chat")
    public ResponseEntity<Void> chat() {
        return ResponseEntity
            .status(HttpStatus.MOVED_PERMANENTLY)
            .header(HttpHeaders.LOCATION, "/api/v1/chat")
            .build();
    }
    // ... 其他端点同理，均无需解析请求体
}
```

> **关于 301 + POST：** HTTP 301 对 POST 请求的语义是不保留 HTTP 方法的（部分客户端可能将 POST 转为 GET）。如果前端确认使用 `fetch`/`axios`（默认自动跟随 301），实际流程是：浏览器收到 301 → 自动重发 POST 到新 URL → 请求体和响应均正常。如果担心兼容性，可降级为 `307 Temporary Redirect`（保证保持 POST 方法）。当前方案先使用 301，如有前端兼容性问题再调整为 307。

#### 12.3.2 SpringDoc OpenAPI 集成

**pom.xml 新增依赖：**

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

**application.yml 配置：**

```yaml
springdoc:
  api-docs:
    path: /api/v1/docs/json
  swagger-ui:
    path: /api/v1/docs/ui
    tags-sorter: alpha
    operations-sorter: method
  default-produces-media-type: application/json
  show-actuator: false
```

**application-dev.yml 覆盖：**
```yaml
springdoc:
  swagger-ui:
    enabled: true
```

**application-prod.yml 覆盖：**
```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

**注解规范示例：**

```java
@Tag(name = "聊天对话", description = "对话交互与流式响应接口")
@RestController
@RequestMapping("/api/v1")
public class ChatV1Controller {

    @Operation(
        summary = "普通对话",
        description = "发送消息并获取 AI 回复，支持自动工具调用（查询时间、内部文档、Prometheus 告警等）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "对话成功",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @ApiResponse(responseCode = "400", description = "参数校验失败"),
        @ApiResponse(responseCode = "429", description = "请求频率超限"),
        @ApiResponse(responseCode = "503", description = "LLM 服务暂不可用")
    })
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
        @RequestBody @Valid ChatRequest request
    ) { ... }
}
```

```java
@Schema(description = "聊天请求")
public class ChatRequest {
    @Schema(description = "会话ID", example = "abc123-def456")
    @JsonProperty("Id")
    private String id;

    @Schema(description = "用户问题", example = "当前系统有哪些活跃告警？")
    @JsonProperty("Question")
    @NotBlank
    private String question;
}
```

#### 12.3.3 SSE 协议文档

编写 `docs/api-sse-protocol.md`：

```markdown
# SSE 流式协议规范 v1

## 连接

- 端点: `POST /api/v1/chat_stream`
- Content-Type: `application/json`
- Accept: `text/event-stream`
- 超时: 5 分钟

## 事件类型

| event | 说明 | 数据格式 |
|-------|------|----------|
| message | 通用消息 | `AgentEvent` JSON |
| content | 文本增量 | `{"type":"CONTENT_CHUNK","data":"文本片段"}` |
| tool_start | 工具调用开始 | `{"type":"TOOL_CALL_START","data":"工具名称"}` |
| tool_end | 工具调用结束 | `{"type":"TOOL_CALL_END","data":"结果摘要"}` |
| error | 错误 | `{"type":"ERROR","data":"错误描述"}` |
| done | 完成 | `{"type":"DONE","data":null,"sessionId":"会话ID"}` |

## AgentEvent 数据结构

{
  "type": "CONTENT_CHUNK | TOOL_CALL_START | TOOL_CALL_END | ERROR | DONE",
  "data": "具体数据",
  "sessionId": "会话ID（仅 DONE 事件携带）"
}
```

#### 12.3.4 DTO 独立化

迁移列表：

| 当前位置 (内部类) | 新位置 (独立文件) |
|-------------------|-------------------|
| `ChatController.ChatRequest` | `dto/ChatRequest.java` |
| `ChatController.ClearRequest` | `dto/ClearRequest.java` |
| `ChatController.SessionInfoResponse` | `dto/SessionInfoResponse.java` |
| `ChatController.ChatResponse` | `dto/ChatResponse.java` |
| `AuthController.LoginRequest` | `dto/LoginRequest.java` |
| `AuthController.LoginResult` | `dto/LoginResult.java` |

#### 12.3.5 Controller 拆分

**拆分前（ChatController：1 个文件，570 行）：**

```
ChatController.java  (@RequestMapping("/api"))
  ├── /chat          — 非流式对话
  ├── /chat_stream   — SSE 流式对话
  ├── /chat/clear    — 清空会话
  ├── /chat/session  — 会话信息
  └── /ai_ops        — AIOps 分析（含 AIOps 路由逻辑）
```

**拆分后（6 个独立 Controller）：**

| Controller | 路径 | 端点 |
|------------|------|------|
| `ChatV1Controller` | `/api/v1` | `/chat`, `/chat_stream`, `/chat/clear`, `/chat/session/{id}` |
| `AIOpsV1Controller` | `/api/v1` | `/ai_ops` |
| `MemoryV1Controller` | `/api/v1` | `/memory/panel`, `/memory/{id}`, `/memory/clear` |
| `AuthV1Controller` | `/api/v1` | `/login` |
| `UploadV1Controller` | `/api/v1` | `/upload`, `/upload/reindex-failed` |
| `HealthV1Controller` | `/api/v1` | `/milvus/health`, `/health`（新增综合健康检查） |

### 12.4 涉及文件

| 文件 | 操作 |
|------|------|
| `controller/v1/ChatV1Controller.java` | **新增** |
| `controller/v1/AIOpsV1Controller.java` | **新增** |
| `controller/v1/MemoryV1Controller.java` | **新增** |
| `controller/v1/AuthV1Controller.java` | **新增** |
| `controller/v1/UploadV1Controller.java` | **新增** |
| `controller/v1/HealthV1Controller.java` | **新增** |
| `controller/legacy/ChatLegacyController.java` | **新增**（301 重定向） |
| `controller/legacy/AIOpsLegacyController.java` | **新增**（301 重定向） |
| `controller/legacy/MemoryLegacyController.java` | **新增**（301 重定向） |
| `controller/legacy/AuthLegacyController.java` | **新增**（301 重定向） |
| `controller/legacy/UploadLegacyController.java` | **新增**（301 重定向） |
| `controller/legacy/MilvusLegacyController.java` | **新增**（301 重定向） |
| `dto/ChatRequest.java` | **新增**（从 ChatController 迁移） |
| `dto/ClearRequest.java` | **新增** |
| `dto/SessionInfoResponse.java` | **新增** |
| `dto/ChatResponse.java` | **新增** |
| `dto/LoginRequest.java` | **新增**（从 AuthController 迁移） |
| `dto/LoginResult.java` | **新增** |
| `controller/ChatController.java` | **删除**（拆分为新版） |
| `controller/AuthController.java` | **删除**（迁移至 AuthV1Controller） |
| `controller/MemoryController.java` | **删除**（迁移至 MemoryV1Controller） |
| `controller/FileUploadController.java` | **删除**（迁移至 UploadV1Controller） |
| `controller/MilvusCheckController.java` | **删除**（迁移至 HealthV1Controller） |
| `pom.xml` | 修改：新增 springdoc-openapi 依赖 |
| `resources/application.yml` | 修改：新增 springdoc 配置 |
| `resources/application-dev.yml` | 修改：启用 Swagger UI |
| `resources/application-prod.yml` | 修改：禁用 Swagger UI |
| `docs/api-sse-protocol.md` | **新增** |

---

## 实施顺序与依赖

三个模块按依赖关系分阶段实施：

```
阶段1: 模块10 配置与环境管理  (基础)
  └─> 阶段2: 模块11 Prompt 管理  (依赖 ModelProperties + PromptProperties)
       └─> 阶段3: 模块12 API 版本化  (独立，可与阶段2并行)
```

**建议实施顺序：**

1. **模块10 先做** — 建立 `ModelProperties` 和配置验证体系，这是 Prompt 管理的基础
2. **模块11 接着做** — Prompt 外部化依赖统一的配置管理，消除硬编码
3. **模块12 独立做** — API 版本化不受前两个模块影响，可在阶段2完成后或与之并行进行

**每个阶段完成后：**
- `mvn clean compile` 验证编译通过
- `mvn spring-boot:run` 验证服务启动正常
- 冒烟测试核心 API 可用性
