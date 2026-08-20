# Spring AI Alibaba 1.1.2.0 版本升级方案（评估文档，暂不实施）

- 日期：2026-08-20
- 状态：方案评估（未实施）
- 目标版本：`spring-ai-alibaba 1.1.2.0`（官方当前推荐稳定版，首个支持 Agent Skills 的版本）
- 依据：官方发布博客《[支持 Agent Skills 和 Multi-agent Patterns，Spring AI Alibaba 1.1.2.0 版本发布！](http://www.java2ai.com/blog/saa-1120-release/)》与官方[版本分布页](https://java2ai.com/docs/versions)

---

## 1. 背景与目标

当前项目（`SuperBizAgent-release-2026-05-17`）钉住的版本：

| 组件 | 当前版本 | 性质 |
|------|---------|------|
| spring-ai-alibaba-bom | 1.1.0.0-RC2 | **候选版**（官方注释：请使用 1.1.0.0 或 1.1.2.0） |
| spring-ai-bom | 1.1.0 | 正式版 |
| spring-ai-alibaba-extensions-bom | 1.1.0.0-RC2 | 候选版 |
| spring-boot-starter-parent | 3.2.0 | 正式版 |

升级动机：
1. **获得 Agent Skills 能力**（`SkillRegistry` / `SkillsAgentHook` / `read_skill`，1.1.2.0 引入，PR #3975）
2. RC2 → 稳定 GA 版，消除候选版风险
3. 附带获得多智能体并行、Graph 并行边/聚合策略（AllOf/AnyOf）、工具 returnDirect 等新能力

范围：**仅评估**。不修改任何代码；升级动作留给后续按 CLAUDE.md 流程执行。

---

## 2. 版本配套关系（升级目标）

官方推荐 BOM 统一管理（详见[版本页](https://java2ai.com/docs/versions)）：

| 组件 | 当前 | 目标 | 说明 |
|------|------|------|------|
| `spring-ai-alibaba-bom` | 1.1.0.0-RC2 | **1.1.2.0** | 主版本，含 agent-framework / graph-core / dashscope starter |
| `spring-ai-bom` | 1.1.0 | **1.1.2** | 底层 Spring AI |
| `spring-ai-alibaba-extensions-bom` | 1.1.0.0-RC2 | **1.1.2.1**（或 1.1.2.0） | 官方 1.1.2.0 配套 1.1.2.1 |
| `spring-boot-starter-parent` | 3.2.0 | **3.5.x**（取最新补丁版） | 1.1.2.0 官方要求 Spring Boot 3.5.x |

> ⚠️ Spring Boot 3.2.0 → 3.5.x 是跨大版本升级，是本次升级工作量最大的部分，不只是改版本号。

---

## 3. 依赖升级清单（pom.xml 逐项）

### 3.1 必须升级

| 位置（pom.xml 行号） | 依赖 | 当前 | 目标 | 原因 |
|---|---|---|---|---|
| L22 | `spring-ai.version` | 1.1.0 | **1.1.2** | 配套 spring-ai-alibaba 1.1.2.0 |
| L23 | `spring-ai-alibaba.version` | 1.1.0.0-RC2 | **1.1.2.0** | 首个支持 Skills 的稳定版 |
| L24 | `spring-ai-alibaba-extensions.version` | 1.1.0.0-RC2 | **1.1.2.1** | 官方配套 |
| L10 | `spring-boot-starter-parent` | 3.2.0 | **3.5.x** | 1.1.2.0 要求 Boot 3.5.x |
| L136-144 | `jsonschema-generator` / `jsonschema-module-jackson` | 4.36.0（显式 pin） | **删除 pin 或升到 4.38.0+** | spring-ai-model 1.1.x 依赖 4.38.0，显式 pin 4.36.0 会冲突 |
| L50-68 | jackson 系列 4 个（core/databind/annotations/jsr310） | 2.17.0（显式 pin） | **删除 pin，交给 BOM** | Spring AI 1.1.x 与 Boot 3.5 均用 Jackson 2.19.x，显式 pin 2.17.0 会冲突 |
| L209 | `springdoc-openapi-starter-webmvc-ui` | 2.6.0 | **2.8.x+** | 2.6.0 不兼容 Boot 3.5 |
| L170 | `resilience4j-spring-boot3` | 2.2.0 | **2.3.x+** | 2.2.0 面向 Boot 3.2；Boot 3.5 建议 2.3.x（以官方兼容矩阵为准） |

### 3.2 无需改版本（由 BOM 管理）

| 依赖 | 说明 |
|------|------|
| `spring-ai-alibaba-starter-dashscope` / `spring-ai-alibaba-agent-framework` | 未写版本号，随 alibaba-bom 1.1.2.0 自动升级 |
| `spring-ai-starter-mcp-client-webflux` | 未写版本号，随 spring-ai-bom 1.1.2 自动升级 |
| `spring-boot-starter-*`（web/validation/data-redis/security/devtools/test） | 随 Boot 3.5.x |
| `caffeine` | 由 Boot BOM 管理 |
| `spring-boot-configuration-processor` | 随 Boot 3.5.x |

### 3.3 建议保持不动（独立于框架升级）

| 依赖 | 版本 | 说明 |
|------|------|------|
| `milvus-sdk-java` | 2.6.10 | 独立 SDK，与 spring-ai 无关 |
| `pdfbox` | 3.0.3 | 独立 |
| `dashscope-sdk-java` | 2.17.0 | ⚠️ 需验证与 alibaba starter 1.1.2.0 内置版本的冲突（见 4.2-7） |
| `gson` 2.10.1 / `bucket4j` 8.10.1 / `jmustache` 1.16 / `lombok` 1.18.30 | 不变 | 纯库，不依赖 Boot/spring-ai 版本 |

---

## 4. 现有代码兼容性分析

已对 `src/main/java` 全量审计框架 API 使用面（67 处 `org.springframework.ai.*` / `com.alibaba.cloud.ai.*` 导入），结论如下。

### 4.1 无需修改（高置信度兼容）

| 使用点 | 说明 |
|--------|------|
| `@Tool` / `@ToolParam` 注解（`org.springframework.ai.tool.annotation`） | 全部 12 个工具类使用；1.1.x 稳定 |
| `ToolCallback` / `ToolCallbackProvider` 注入 | `ReactAgentRunner` L65/L397；稳定 |
| `SystemMessage` / `UserMessage` / `AssistantMessage` | 稳定 |
| `ChatModel` / `ChatResponse` / `Prompt` | `DashScopeLlmProvider`、`IntentRouter`、`AIOpsEvaluator`、rewrite 三个策略；稳定 |
| `DashScopeApi.builder().apiKey(key).build()` | 6 个文件；稳定 |
| `ReactAgent.builder().name().description().model().systemPrompt().methodTools().tools().outputKey()` | 核心 Builder 方法，1.1.2.0 保留（属增强而非破坏）；**建议编译验证** |
| `agent.call()` / `agent.stream()` | 1.1.2.0 新增带额外参数的 invoke/call 重载（#4031），旧签名保留 |
| `SupervisorAgent.builder().name().description().model().systemPrompt().subAgents().build()` + `.invoke()` | 1.1.2.0 对 Supervisor 是增强（并行子智能体），非破坏 |
| `OverAllState.value("planner_plan")` | `ReactAgentRunner` L408；稳定 |
| `OutputType.AGENT_MODEL_STREAMING` / `AGENT_TOOL_FINISHED` + `StreamingOutput` 桥接 | 1.1.2.0 新增 streamMessages API，旧 `stream()` 路径保留；**建议编译验证** |
| MCP 配置 `spring.ai.mcp.client.sse.connections.tencent-cls` | 1.1.x 属性格式稳定 |
| 全部自定义配置块 `rag.*` / `memory.*` / `prometheus.*` / `cls.*` / `document.*` / `superbiz.*` / `dashscope.api.key` | 与框架版本无关 |
| 项目自有代码（controllers / services / security / interceptor / exception / prompt 模板 / 前端 static） | 不依赖框架版本 |

### 4.2 需要代码修改 / 重点验证（按风险从高到低）

**① 高风险：`DashScopeChatOptions` 构建风格（6 个文件）**
- 现状：`DashScopeChatOptions.builder().withModel(...).withTemperature(...).withMaxToken(...).withTopP(...)`（withXxx 老风格）
- 涉及文件：`ReactAgentRunner` L373-376、`DashScopeLlmProvider` L78-81、`SummaryGenerator` L124-127、`AIOpsEvaluator` L148-151、`IntentRouter` L77-80、`QueryRewriteService` L260-263
- 风险：RC2 jar 中 withXxx 与新式方法并存（已解包验证）；1.1.2.0 若移除 withXxx，需改为新式命名：
  ```java
  DashScopeChatOptions.builder()
      .model(config.getName())
      .temperature(config.getTemperature())
      .maxTokens(config.getMaxToken())   // 注意 maxToken → maxTokens 命名差异
      .topP(config.getTopP())
      .build()
  ```
- 判断方法：升级后第一个 `mvn clean install` 编译错误即可确认。**这是升级后第一步必须验证的点。**

**② 中风险：`ReactAgent.Builder.methodTools()` 与 `outputKey()`**
- `methodTools(buildMethodToolsArray())`（ReactAgentRunner L276/L292/L309）与 `outputKey("planner_plan")`（L294/L311）
- 属核心 API，1.1.2.0 大概率保留；但官方文档示例已改用 `.tools()` 风格，编译验证即可

**③ 中风险：流式事件枚举名**
- `OutputType.AGENT_MODEL_STREAMING` / `AGENT_TOOL_FINISHED`（ReactAgentRunner L169/L174）
- 1.1.2.0 新增 `streamMessages` API（#4031），若旧枚举更名需同步改 `executeStream()` 的 AgentEvent 桥接

**④ 中风险：Spring Boot 3.5 连带修改**
- `springdoc` 2.6.0 → 2.8.x+：`@Operation` 注解用法不变，仅版本升级
- `resilience4j` 2.2.0 → 2.3.x+：`@CircuitBreaker` / `@RateLimiter` 注解用法不变
- Spring Security 6.5（Boot 3.5 自带）：`SecurityFilterChain` Lambda DSL 写法不变（`security/ApiKeyAuthenticationFilter.java` 等），需回归测试登录/鉴权
- `spring-boot-starter-data-redis`：Redis 配置不变

**⑤ 低风险：dashscope 配置属性名**
- 项目同时使用 `spring.ai.dashscope.api-key`（starter 自动装配，application.yml L26）与自定义 `dashscope.api.key`（L69）
- 验证 1.1.2.0 starter 是否仍读 `spring.ai.dashscope.api-key`；若前缀变更需同步改 yml

**⑥ 低风险：`dashscope-sdk-java` 版本冲突**
- `VectorEmbeddingService` 直接使用 `com.alibaba.dashscope.*`（TextEmbedding API）
- 验证 1.1.2.0 starter 内置的 dashscope-sdk 版本与显式 2.17.0 的冲突（建议对齐到 starter 管理版本，或用 `mvn dependency:tree` 排查重复）

**⑦ 可选新增（不阻塞升级）：Agent Skills 接线**
- 升级后新增类：`com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry`（FileSystem/Classpath 实现）、`com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook`
- 用法：`ReactAgent.builder().hooks(List.of(skillsHook)).saver(new MemorySaver()).build()`
- 注意：当前项目 `buildReactAgent()` 未挂 `.saver()`；`MemorySaver`（`com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver`）在 RC2 中已存在，兼容。纯新增，不影响现有功能

### 4.3 运行时行为变化提醒（不影响现有功能，但值得知道）

| 变化 | 影响 |
|------|------|
| Supervisor / LlmRouting 支持并行子智能体 | 现有 AIOps Planner-Executor **串行**编排默认行为不变 |
| Graph 并行条件边 / AllOf / AnyOf 聚合 | 项目未用 Graph 自定义编排，无影响 |
| 工具 `returnDirect`、`ToolContextHelper`（#4139/#4163） | 新能力，现有工具不启用则不生效 |
| 序列化与 MergeStrategy 修复（#3969/#4129） | 对现有 prompt 渲染与对话历史无影响 |
| Admin 包更名 `@agentscope-ai/flow` → `@spark-ai/flow` | 仅影响 Admin Flow UI（JS 侧），本项目未用 |

---

## 5. 升级步骤（按 CLAUDE.md 流程执行）

1. `git checkout master && git pull` → 新建 feature 分支（如 `feature/spring-ai-alibaba-1.1.2.0-upgrade`）
2. 修改 `pom.xml`：按第 3 节清单调整版本
3. `mvn clean install` 编译：
   - 重点看 4.2-① 的 withXxx 编译错误 → 按新式命名迁移
   - 逐个处理 4.2-②③ 的编译错误（如有）
4. 启动验证冒烟测试：
   - `GET /milvus/health`（Milvus 连通）
   - 上传文档 → 向量化（`POST /api/upload`）
   - `POST /api/chat`（普通对话 + Agentic RAG 开关测试）
   - `POST /api/ai_ops`（Supervisor 编排）
   - 腾讯 CLS MCP 日志查询（验证 MCP 配置兼容）
   - 登录/鉴权、限流回归
5. 使用 code review expert skill 做代码审查
6. 解决审查问题 → 再次编译测试 → 提交并推送（失败重试 5 次，仍失败则停止）

---

## 6. 风险与回退

| 风险 | 应对 |
|------|------|
| Boot 3.2 → 3.5 跨大版本，第三方 starter 兼容性未知 | 逐项编译验证；预留 1-2 天；建议先在分支完整跑回归 |
| 1.1.2.0 jar 无法从本机网络验证 API 细节 | 本方案 4.2 的"编译验证"项即为兜底手段，编译错误即精确暴露差异 |
| dashscope-sdk 重复依赖 | `mvn dependency:tree` 排查并收敛 |
| 升级失败 | 分支未合并，`git checkout master` 即回退；或保留原 pom 备份 |

---

## 7. 参考资料

- 官方发布博客：[支持 Agent Skills 和 Multi-agent Patterns，Spring AI Alibaba 1.1.2.0 版本发布！](http://www.java2ai.com/blog/saa-1120-release/)
- 官方版本分布：[https://java2ai.com/docs/versions](https://java2ai.com/docs/versions)
- 官方 Release Notes：[https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.0](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.0)
- Agent Skills 文档：[Skills 技能 | Spring AI Alibaba](http://java2ai.com/en/docs/frameworks/agent-framework/tutorials/skills/)
- 本机验证事实：
  - `spring-ai-model-1.1.0.pom` 声明 `jsonschema-generator 4.38.0`、jackson 2.19.2（与项目显式 pin 冲突，见 3.1）
  - `spring-ai-alibaba-dashscope-1.1.0.0-RC2.jar` 中 `DashScopeChatOptions` 同时含 withXxx 与新式 builder 方法（验证了 4.2-① 的现状）
  - `spring-ai-alibaba-agent-framework-1.1.0.0-RC2.jar` 的 `ReactAgent.Builder` 含 hooks/saver/interceptors/outputKey 等（验证了 4.1/4.2-⑦ 的兼容性判断）
