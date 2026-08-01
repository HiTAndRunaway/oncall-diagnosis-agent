# 配置管理 / Prompt 管理 / API 版本化 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现分层模型配置化、Prompt 外部化管理、API 版本化三个 P2 模块的改进

**Architecture:** 3 阶段实施：阶段1 建立 `ModelProperties` 分层配置体系（基础层），阶段2 基于 `PromptManager` 将 19 个内联 Prompt 外部化为 Mustache 双语模板（依赖阶段1），阶段3 将 12 个 API 端点迁移到 `/api/v1` 并引入 SpringDoc OpenAPI（独立实施）

**Tech Stack:** Spring Boot 3.2, Spring AI Alibaba, Mustache (JMustache), SpringDoc OpenAPI 2.6, Maven

## Global Constraints

- Java 17, Spring Boot 3.2.0
- 每个阶段完成后：`mvn clean compile` 通过 + `mvn spring-boot:run` 启动正常
- 配置变更后需重启生效（不要求运行时热更新）
- Prompt 仅启动时加载，不要求热重载
- Swagger UI 仅在 dev profile 可访问
- 旧 API 路径返回 301 永久重定向
- 所有代码注释和文档使用中文

---

## 阶段1：模块10 — 配置与环境管理

### Task 1.1: 创建 ModelProperties 配置类

**Files:**
- Create: `src/main/java/org/example/config/ModelProperties.java`

**Interfaces:**
- Produces: `ModelProperties` record with nested `ModelConfig`, `AiopsModels` records
- Produces: `ModelConfig(String name, double temperature, int maxToken, double topP)` with JSR-303 annotations

- [ ] **Step 1: 创建 ModelProperties.java**

```java
package org.example.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 分层模型配置属性
 * 为每个任务场景提供独立的模型名称和参数配置
 */
@ConfigurationProperties(prefix = "ai.model")
@Validated
public class ModelProperties {

    @NotNull @Valid
    private ModelConfig chat = new ModelConfig("qwen3-max", 0.7, 2000, 0.9);

    @NotNull @Valid
    private AiopsModels aiops = new AiopsModels();

    @NotNull @Valid
    private ModelConfig lightweight = new ModelConfig("qwen-turbo", 0.3, 2000, 0.9);

    @NotNull @Valid
    private ModelConfig reasoning = new ModelConfig("qwen3-max", 0.3, 4000, 0.9);

    @NotNull @Valid
    private ModelConfig rewrite = new ModelConfig("qwen-turbo", 0.3, 500, 0.9);

    // getters and setters
    public ModelConfig getChat() { return chat; }
    public void setChat(ModelConfig chat) { this.chat = chat; }
    public AiopsModels getAiops() { return aiops; }
    public void setAiops(AiopsModels aiops) { this.aiops = aiops; }
    public ModelConfig getLightweight() { return lightweight; }
    public void setLightweight(ModelConfig lightweight) { this.lightweight = lightweight; }
    public ModelConfig getReasoning() { return reasoning; }
    public void setReasoning(ModelConfig reasoning) { this.reasoning = reasoning; }
    public ModelConfig getRewrite() { return rewrite; }
    public void setRewrite(ModelConfig rewrite) { this.rewrite = rewrite; }

    /**
     * 单个模型配置
     */
    public static class ModelConfig {
        @NotBlank(message = "模型名称不能为空")
        private String name;

        @DecimalMin(value = "0.0", message = "temperature 不能小于 0.0")
        @DecimalMax(value = "2.0", message = "temperature 不能大于 2.0")
        private double temperature;

        @Min(value = 1, message = "maxToken 最小为 1")
        @Max(value = 32768, message = "maxToken 最大为 32768")
        private int maxToken;

        @DecimalMin(value = "0.0", message = "topP 不能小于 0.0")
        @DecimalMax(value = "1.0", message = "topP 不能大于 1.0")
        private double topP;

        public ModelConfig() {}

        public ModelConfig(String name, double temperature, int maxToken, double topP) {
            this.name = name;
            this.temperature = temperature;
            this.maxToken = maxToken;
            this.topP = topP;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxToken() { return maxToken; }
        public void setMaxToken(int maxToken) { this.maxToken = maxToken; }
        public double getTopP() { return topP; }
        public void setTopP(double topP) { this.topP = topP; }
    }

    /**
     * AIOps 三 Agent 独立模型配置
     */
    public static class AiopsModels {
        @NotNull @Valid
        private ModelConfig supervisor = new ModelConfig("qwen3-max", 0.3, 8000, 0.9);

        @NotNull @Valid
        private ModelConfig planner = new ModelConfig("qwen3-max", 0.3, 8000, 0.9);

        @NotNull @Valid
        private ModelConfig executor = new ModelConfig("qwen-turbo", 0.3, 4000, 0.9);

        public ModelConfig getSupervisor() { return supervisor; }
        public void setSupervisor(ModelConfig supervisor) { this.supervisor = supervisor; }
        public ModelConfig getPlanner() { return planner; }
        public void setPlanner(ModelConfig planner) { this.planner = planner; }
        public ModelConfig getExecutor() { return executor; }
        public void setExecutor(ModelConfig executor) { this.executor = executor; }
    }
}
```

- [ ] **Step 2: 注册 @ConfigurationProperties**

在 `Main.java` 添加 `@EnableConfigurationProperties(ModelProperties.class)` 注解，或在 `ModelProperties` 上确保有 `@ConfigurationProperties` + Spring component scan 覆盖。

```bash
# 检查 Main.java 位置并确认
grep -n "EnableConfigurationProperties\|SpringBootApplication" src/main/java/org/example/Main.java
```

如果 `Main.java` 已有 `@EnableConfigurationProperties`，追加 `ModelProperties.class`；否则添加：
```java
@EnableConfigurationProperties({ModelProperties.class})
```

- [ ] **Step 3: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/config/ModelProperties.java
git add src/main/java/org/example/Main.java  # 如果修改了
git commit -m "feat(config): add ModelProperties with per-scenario model configuration and JSR-303 validation"
```

---

### Task 1.2: 新增 ai.model YAML 配置段

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 application.yml 添加 ai.model 配置段**

在 `application.yml` 末尾追加：

```yaml
# ==================== 分层模型配置 ====================
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

- [ ] **Step 2: 编译验证配置绑定**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS（验证 YAML 格式正确，不会阻止编译，但能确保语法正确）

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat(config): add ai.model YAML configuration for per-scenario model settings"
```

---

### Task 1.3: 修改 ReactAgentRunner 使用 ModelProperties

**Files:**
- Modify: `src/main/java/org/example/agent/ReactAgentRunner.java`

**Interfaces:**
- Consumes: `ModelProperties` (from Task 1.1)
- Modifies: `buildChatModel()`, `buildPlannerAgent()`, `buildExecutorAgent()`, the supervisor build call, `generateFallbackReport()`

- [ ] **Step 1: 注入 ModelProperties 并修改 buildChatModel**

在 `ReactAgentRunner.java` 中：

```java
// 新增注入
@Autowired
private ModelProperties modelProperties;

// 修改 buildChatModel（约第357行）
private DashScopeChatModel buildChatModel(ModelProperties.ModelConfig config) {
    DashScopeApi api = DashScopeApi.builder().apiKey(dashScopeApiKey).build();
    return DashScopeChatModel.builder()
            .dashScopeApi(api)
            .defaultOptions(DashScopeChatOptions.builder()
                    .withModel(config.getName())
                    .withTemperature(config.getTemperature())
                    .withMaxToken(config.getMaxToken())
                    .withTopP(config.getTopP())
                    .build())
            .build();
}

// 同时保留无参重载（用于 chat 场景，使用默认 chat 配置）
private DashScopeChatModel buildChatModel() {
    return buildChatModel(modelProperties.getChat());
}

// 删除旧的 buildChatModel(double temperature, int maxToken, double topP) 方法
```

- [ ] **Step 2: 修改 buildReactAgent**

```java
// buildReactAgent（约第260行）中：
// 旧: DashScopeChatModel chatModel = buildChatModel(0.7, 2000, 0.9);
// 新:
DashScopeChatModel chatModel = buildChatModel(modelProperties.getChat());
```

- [ ] **Step 3: 修改 AIOps Agent 构建方法**

```java
// buildPlannerAgent（约第275行）
// 旧: 使用参数传入的 chatModel
// 新: 使用独立的 planner 模型
private ReactAgent buildPlannerAgent(ToolCallback[] toolCallbacks) {
    DashScopeChatModel plannerModel = buildChatModel(modelProperties.getAiops().getPlanner());
    return ReactAgent.builder()
            .name("planner_agent")
            .description("负责拆解告警、规划与再规划步骤")
            .model(plannerModel)
            .systemPrompt(buildPlannerPrompt())
            .methodTools(buildAIOpsMethodToolsArray())
            .tools(toolCallbacks)
            .outputKey("planner_plan")
            .build();
}

// buildExecutorAgent（约第291行）
// 新: 使用独立的 executor 模型
private ReactAgent buildExecutorAgent(ToolCallback[] toolCallbacks) {
    DashScopeChatModel executorModel = buildChatModel(modelProperties.getAiops().getExecutor());
    return ReactAgent.builder()
            .name("executor_agent")
            .description("负责执行 Planner 的首个步骤并及时反馈")
            .model(executorModel)
            .systemPrompt(buildExecutorPrompt())
            .methodTools(buildAIOpsMethodToolsArray())
            .tools(toolCallbacks)
            .outputKey("executor_feedback")
            .build();
}
```

- [ ] **Step 4: 修改 executeOrchestration 方法**

```java
// executeOrchestration（约第193行）
@Override
public AiOpsResult executeOrchestration(String taskPrompt) {
    log.info("开始执行 AI Ops 多 Agent 协作流程");

    ToolCallback[] toolCallbacks = getToolCallbacks();

    // 新: 各 Agent 使用独立模型，不再共用一个 chatModel
    ReactAgent plannerAgent = buildPlannerAgent(toolCallbacks);
    ReactAgent executorAgent = buildExecutorAgent(toolCallbacks);

    DashScopeChatModel supervisorModel = buildChatModel(modelProperties.getAiops().getSupervisor());
    SupervisorAgent supervisorAgent = SupervisorAgent.builder()
            .name("ai_ops_supervisor")
            .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
            .model(supervisorModel)
            .systemPrompt(buildSupervisorSystemPrompt())
            .subAgents(List.of(plannerAgent, executorAgent))
            .build();
    // ... 后续逻辑不变
}
```

- [ ] **Step 5: 修改 generateFallbackReport**

```java
// generateFallbackReport（约第432行）
// 旧: LlmProvider.ChatOptions.aiOps(DashScopeChatModel.DEFAULT_MODEL_NAME)
// 新:
private AiOpsResult generateFallbackReport(String taskPrompt) {
    try {
        String forcePrompt = String.format("""...""", taskPrompt);
        ModelProperties.ModelConfig aiopsCfg = modelProperties.getAiops().getPlanner();
        String report = llmProvider.chat("你是一个企业级 SRE。", forcePrompt,
                LlmProvider.ChatOptions.aiOps(aiopsCfg.getName()));
        // ... 其余不变
    }
}
```

- [ ] **Step 6: 添加 import**

```java
import org.example.config.ModelProperties;
```

- [ ] **Step 7: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/agent/ReactAgentRunner.java
git commit -m "refactor(agent): use ModelProperties for per-agent model configuration in ReactAgentRunner"
```

---

### Task 1.4: 修改 SummaryGenerator 使用 ModelProperties

**Files:**
- Modify: `src/main/java/org/example/service/SummaryGenerator.java`

- [ ] **Step 1: 注入 ModelProperties 并修改 generateSummary**

```java
// 新增注入
@Autowired
private ModelProperties modelProperties;

// 修改 generateSummary（约第110行）
// 旧: String modelName = props.getSummary().getModel();
// 新: 使用 lightweight 配置
private String generateSummary(String prompt) {
    try {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();

        ModelProperties.ModelConfig cfg = modelProperties.getLightweight();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(cfg.getName())
                        .withTemperature(cfg.getTemperature())
                        .withMaxToken(cfg.getMaxToken())
                        .withTopP(cfg.getTopP())
                        .build())
                .build();

        String response = chatModel.call(prompt);
        if (response != null && !response.isEmpty()) {
            return response;
        }
    } catch (Exception e) {
        logger.error("LLM 摘要生成调用失败", e);
    }
    return null;
}

// 添加 import
import org.example.config.ModelProperties;
```

- [ ] **Step 2: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/SummaryGenerator.java
git commit -m "refactor(summary): use ModelProperties.lightweight config in SummaryGenerator"
```

---

### Task 1.5: 修改 MemoryExtractor 修复硬编码并改用 ModelProperties

**Files:**
- Modify: `src/main/java/org/example/service/MemoryExtractor.java`

- [ ] **Step 1: 注入 ModelProperties 并修改 callLlm**

```java
// 新增注入
@Autowired
private ModelProperties modelProperties;

// 修改 callLlm（约第268行）
// 当前方法签名: private String callLlm(String model, String systemPrompt, String userMessage)
// 改为使用 ModelProperties.lightweight
private String callLlm(String systemPrompt, String userMessage) {
    try {
        ModelProperties.ModelConfig cfg = modelProperties.getLightweight();
        return llmClient.callWithSystemPrompt(cfg.getName(), systemPrompt, userMessage,
                cfg.getTemperature(), cfg.getMaxToken());
    } catch (Exception e) {
        logger.warn("LLM 调用失败: {}", e.getMessage());
        return null;
    }
}
```

- [ ] **Step 2: 修改 doExtract 中的调用**

```java
// doExtract（约第109行）
// 旧: String llmResponse = callLlm(model, "你是一个记忆提取器。", extractionPrompt);
// 旧: String model = memoryProperties.getExtraction().getModel();
// 删除 model 变量，改为:
String llmResponse = callLlm("你是一个记忆提取器。", extractionPrompt);
```

- [ ] **Step 3: 修改 resolveConflict 中的硬编码**

```java
// resolveConflict（约第239行）
// 旧: String response = callLlm("qwen-turbo", "你是一个记忆冲突判断器。", prompt);
// 新:
String response = callLlm("你是一个记忆冲突判断器。", prompt);
```

- [ ] **Step 4: 修改 resolveMerge 中的硬编码**

```java
// resolveMerge（约第264行）
// 旧: String response = callLlm("qwen-turbo", "你是一个信息整合器。", prompt);
// 新:
String response = callLlm("你是一个信息整合器。", prompt);
```

- [ ] **Step 5: 添加 import**

```java
import org.example.config.ModelProperties;
```

- [ ] **Step 6: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/service/MemoryExtractor.java
git commit -m "fix(memory): remove hardcoded model names, use ModelProperties.lightweight in MemoryExtractor"
```

---

### Task 1.6: 修改 QueryRewriteService 使用 ModelProperties

**Files:**
- Modify: `src/main/java/org/example/service/rewrite/QueryRewriteService.java`

- [ ] **Step 1: 注入 ModelProperties 并修改 createRewriteChatModel**

```java
// 新增注入
private final ModelProperties modelProperties;

// 修改构造函数
public QueryRewriteService(QueryRewriteProperties properties,
                           StringRedisTemplate redisTemplate,
                           @Value("${dashscope.api.key}") String dashscopeApiKey,
                           ModelProperties modelProperties) {
    this.properties = properties;
    this.redisTemplate = redisTemplate;
    this.dashscopeApiKey = dashscopeApiKey;
    this.modelProperties = modelProperties;
}

// 修改 createRewriteChatModel（约第248行）
// 旧: .withModel(properties.getModel())
//     .withTemperature(0.3)
//     .withMaxToken(500)
//     .withTopP(0.9)
// 新:
private DashScopeChatModel createRewriteChatModel() {
    DashScopeApi api = DashScopeApi.builder()
            .apiKey(dashscopeApiKey)
            .build();
    ModelProperties.ModelConfig cfg = modelProperties.getRewrite();
    return DashScopeChatModel.builder()
            .dashScopeApi(api)
            .defaultOptions(DashScopeChatOptions.builder()
                    .withModel(cfg.getName())
                    .withTemperature(cfg.getTemperature())
                    .withMaxToken(cfg.getMaxToken())
                    .withTopP(cfg.getTopP())
                    .build())
            .build();
}

// 添加 import
import org.example.config.ModelProperties;
```

- [ ] **Step 2: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/rewrite/QueryRewriteService.java
git commit -m "refactor(rewrite): use ModelProperties.rewrite config in QueryRewriteService"
```

---

### Task 1.7: 统一 API Key 注入（DashScopeApiProperties）

**Files:**
- Create: `src/main/java/org/example/config/DashScopeApiProperties.java`
- Modify: `src/main/java/org/example/agent/DashScopeLlmProvider.java`

- [ ] **Step 1: 创建 DashScopeApiProperties.java**

```java
package org.example.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * DashScope API Key 统一配置
 * 业务代码统一通过此类注入，不再散落 @Value("${dashscope.api.key}")
 */
@ConfigurationProperties(prefix = "dashscope.api")
@Validated
public class DashScopeApiProperties {

    @NotBlank(message = "DashScope API Key 不能为空")
    private String key;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
}
```

- [ ] **Step 2: 修改 DashScopeLlmProvider 使用 DashScopeApiProperties**

```java
// 在 DashScopeLlmProvider.java 中:
// 旧: @Value("${spring.ai.dashscope.api-key}")
//     private String apiKey;
// 新:
@Autowired
private DashScopeApiProperties dashScopeApiProperties;

// buildModel 中:
// 旧: DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
// 新:
private DashScopeChatModel buildModel(ChatOptions options) {
    DashScopeApi api = DashScopeApi.builder()
            .apiKey(dashScopeApiProperties.getKey())
            .build();
    // ... 其余不变
}

// 添加 import
import org.example.config.DashScopeApiProperties;
```

- [ ] **Step 3: 在 Main.java 注册新的 @ConfigurationProperties**

如果 `Main.java` 使用了 `@EnableConfigurationProperties`，追加 `DashScopeApiProperties.class`。

- [ ] **Step 4: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/config/DashScopeApiProperties.java
git add src/main/java/org/example/agent/DashScopeLlmProvider.java
git add src/main/java/org/example/Main.java
git commit -m "refactor(config): add DashScopeApiProperties, unify API key injection in DashScopeLlmProvider"
```

---

### Task 1.8: 创建 FeatureFlagStartupChecker

**Files:**
- Create: `src/main/java/org/example/config/FeatureFlagStartupChecker.java`
- Create: `docs/feature-flags.md`（项目根目录下的 docs/）

- [ ] **Step 1: 创建 docs/feature-flags.md**

在 `SuperBizAgent-release-2026-05-17/docs/` 目录下创建：

```markdown
# 特性开关

> 最后更新：2026-08-01

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
| `aiops.eval.enabled` | true | - | LLM-as-Judge 质量评估 |
```

- [ ] **Step 2: 创建 FeatureFlagStartupChecker.java**

```java
package org.example.config;

import io.milvus.client.MilvusServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 特性开关启动时依赖校验
 * 在应用启动后检查各开关的前置条件，发现不匹配时打 WARN 日志
 */
@Component
public class FeatureFlagStartupChecker {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagStartupChecker.class);

    @Value("${memory.enabled:true}")
    private boolean memoryEnabled;

    @Value("${rag.agentic.enabled:false}")
    private boolean agenticRagEnabled;

    @Value("${superbiz.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${superbiz.rate-limit.enabled:false}")
    private boolean rateLimitEnabled;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private MilvusServiceClient milvusClient;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        // memory.enabled=true 但 Redis 不可用
        if (memoryEnabled && redisTemplate == null) {
            log.warn("[FeatureFlag] memory.enabled=true 但 Redis 不可用，记忆功能可能异常");
        }

        // rag.agentic.enabled=true 时提示需要 biz collection 数据
        if (agenticRagEnabled) {
            log.info("[FeatureFlag] rag.agentic.enabled=true，请确保 biz collection 中有数据");
        }

        // rate-limit.enabled=true 但 security.enabled=false
        if (rateLimitEnabled && !securityEnabled) {
            log.warn("[FeatureFlag] rate-limit.enabled=true 但 security.enabled=false，"
                    + "限流依赖用户身份识别，建议同时开启安全认证");
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/config/FeatureFlagStartupChecker.java
git add docs/feature-flags.md
git commit -m "feat(config): add FeatureFlagStartupChecker and feature-flags documentation"
```

---

### Task 1.9: 阶段1 集成测试与验证

- [ ] **Step 1: 完整编译**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动验证（配置校验）**

```bash
cd SuperBizAgent-release-2026-05-17 && timeout 30 mvn spring-boot:run 2>&1 | head -100
```

Expected: 应用启动无 `BindException`，日志中出现 `ai.model` 相关配置绑定信息。

- [ ] **Step 3: 验证配置校验生效**

临时在 `application.yml` 中将 `ai.model.chat.temperature` 设为 `3.0`（超出 0-2 范围），启动应失败：
```bash
# 临时修改后启动，预期启动失败
# 确认后还原配置
```

- [ ] **Step 4: Commit 阶段1 整体**

```bash
git add -A
git commit -m "chore: Phase 1 complete - model configuration, startup validation, feature flags"
```

---

## 阶段2：模块11 — Prompt 管理

### Task 2.1: 添加 Mustache 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 在 pom.xml 添加 JMustache 依赖**

```xml
<!-- Mustache 模板引擎 - Prompt 模板渲染 -->
<dependency>
    <groupId>com.samskivert</groupId>
    <artifactId>jmustache</artifactId>
    <version>1.16</version>
</dependency>
```

在 `pom.xml` 的 `<dependencies>` 节末尾（`</dependencies>` 之前）添加。

- [ ] **Step 2: 验证依赖下载**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn dependency:resolve
```

Expected: BUILD SUCCESS, jmustache-1.16.jar 成功下载

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add JMustache dependency for prompt template rendering"
```

---

### Task 2.2: 创建 PromptProperties 配置类

**Files:**
- Create: `src/main/java/org/example/config/PromptProperties.java`

**Interfaces:**
- Produces: `PromptProperties` with `defaultLang`, `mappings` Map

- [ ] **Step 1: 创建 PromptProperties.java**

```java
package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Prompt 管理配置属性
 */
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {

    /** 默认语言，当请求未指定语言时使用 */
    private String defaultLang = "zh";

    /** 自定义 Prompt 路径映射，key=Prompt标识, value=文件路径 */
    private Map<String, String> mappings = new HashMap<>();

    public String getDefaultLang() { return defaultLang; }
    public void setDefaultLang(String defaultLang) { this.defaultLang = defaultLang; }
    public Map<String, String> getMappings() { return mappings; }
    public void setMappings(Map<String, String> mappings) { this.mappings = mappings; }
}
```

- [ ] **Step 2: 在 application.yml 添加 prompts 配置**

```yaml
# Prompt 管理配置
prompts:
  default-lang: zh
```

- [ ] **Step 3: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/config/PromptProperties.java
git add src/main/resources/application.yml
git commit -m "feat(prompt): add PromptProperties configuration for prompt management"
```

---

### Task 2.3: 创建 PromptManager 核心组件

**Files:**
- Create: `src/main/java/org/example/service/PromptManager.java`

**Interfaces:**
- Consumes: `PromptProperties` (from Task 2.2)
- Produces: `PromptManager.render(String key, Map<String, Object> vars, String lang)` → String
- Produces: `PromptManager.PromptMeta getMeta(String key, String lang)` → PromptMeta

- [ ] **Step 1: 创建 PromptManager.java**

```java
package org.example.service;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import jakarta.annotation.PostConstruct;
import org.example.config.PromptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prompt 管理器
 * 启动时加载 prompts/ 目录下的所有 .md 文件，支持 Mustache 模板渲染和中英文双语
 */
@Component
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL);

    private final PromptProperties properties;
    private final Mustache.Compiler compiler;

    /** 已加载的 Prompt：key = "zh:chat/system-prompt" */
    private final Map<String, CompiledPrompt> prompts = new ConcurrentHashMap<>();

    public PromptManager(PromptProperties properties) {
        this.properties = properties;
        this.compiler = Mustache.compiler().defaultValue("");
    }

    @PostConstruct
    public void loadAll() {
        log.info("开始加载 Prompt 文件...");
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:prompts/**/*.md");
            for (Resource resource : resources) {
                loadSingle(resource);
            }
            log.info("Prompt 加载完成，共 {} 个模板", prompts.size());
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 文件加载失败，应用无法启动", e);
        }
    }

    private void loadSingle(Resource resource) {
        try {
            String path = resource.getURL().getPath();
            // 解析路径: .../prompts/zh/chat/system-prompt.md → lang=zh, key=chat/system-prompt
            String relativePath = path.substring(path.indexOf("prompts/"));
            String withoutPrefix = relativePath.substring("prompts/".length()); // zh/chat/system-prompt.md
            String[] parts = withoutPrefix.split("/", 2);
            String lang = parts[0];                    // zh
            String key = parts[1].replace(".md", "");  // chat/system-prompt

            String content = readResource(resource);
            ParsedPrompt parsed = parseFrontmatter(content);

            Template template = compiler.compile(parsed.body);
            CompiledPrompt cp = new CompiledPrompt(parsed.meta, template, path);
            prompts.put(lang + ":" + key, cp);

            log.debug("已加载 Prompt: {}:{}", lang, key);
        } catch (Exception e) {
            log.error("加载 Prompt 失败: {}", resource.getFilename(), e);
            throw new IllegalStateException("无法加载 Prompt 文件: " + resource.getFilename(), e);
        }
    }

    private String readResource(Resource resource) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private ParsedPrompt parseFrontmatter(String content) {
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (m.find()) {
            PromptMeta meta = parseMeta(m.group(1));
            String body = m.group(2);
            return new ParsedPrompt(meta, body);
        }
        // 无 frontmatter → 整个文件作为模板正文
        return new ParsedPrompt(new PromptMeta(), content);
    }

    private PromptMeta parseMeta(String yamlBlock) {
        PromptMeta meta = new PromptMeta();
        for (String line : yamlBlock.split("\n")) {
            String[] kv = line.split(":", 2);
            if (kv.length < 2) continue;
            String key = kv[0].trim();
            String value = kv[1].trim();
            switch (key) {
                case "version": meta.setVersion(Integer.parseInt(value)); break;
                case "modified": meta.setModified(value); break;
                case "author": meta.setAuthor(value); break;
                case "changes": meta.setChanges(value); break;
                case "model": meta.setModel(value); break;
            }
        }
        return meta;
    }

    /**
     * 渲染 Prompt 模板
     *
     * @param key       Prompt 标识，如 "chat/system-prompt"
     * @param variables 模板变量
     * @param lang      语言代码 ("zh" / "en")，null 则使用默认语言
     * @return 渲染后的完整 Prompt 文本
     */
    public String render(String key, Map<String, Object> variables, String lang) {
        String langKey = resolveLang(lang);
        CompiledPrompt cp = resolvePrompt(key, langKey);
        try {
            return cp.template.execute(variables != null ? variables : Collections.emptyMap());
        } catch (Exception e) {
            log.error("Prompt 渲染失败: key={}, lang={}", key, langKey, e);
            throw new IllegalStateException(
                    "Prompt 渲染失败: key=" + key + ", lang=" + langKey + " - " + e.getMessage(), e);
        }
    }

    /**
     * 获取 Prompt 元数据
     */
    public PromptMeta getMeta(String key, String lang) {
        String langKey = resolveLang(lang);
        CompiledPrompt cp = resolvePrompt(key, langKey);
        return cp.meta;
    }

    private String resolveLang(String lang) {
        if (lang != null && !lang.isEmpty()) return lang;
        return properties.getDefaultLang();
    }

    private CompiledPrompt resolvePrompt(String key, String langKey) {
        // 1. 尝试请求语言
        CompiledPrompt cp = prompts.get(langKey + ":" + key);
        if (cp != null) return cp;

        // 2. 回退到默认语言
        String defaultLang = properties.getDefaultLang();
        if (!defaultLang.equals(langKey)) {
            cp = prompts.get(defaultLang + ":" + key);
            if (cp != null) {
                log.debug("Prompt '{}' 在语言 '{}' 下未找到，回退到 '{}'", key, langKey, defaultLang);
                return cp;
            }
        }

        // 3. 找不到 → 抛异常
        throw new IllegalStateException(
                "Prompt not found: key=" + key + ", lang=" + langKey);
    }

    // ===== 内部类型 =====

    private static class ParsedPrompt {
        final PromptMeta meta;
        final String body;
        ParsedPrompt(PromptMeta meta, String body) { this.meta = meta; this.body = body; }
    }

    private static class CompiledPrompt {
        final PromptMeta meta;
        final Template template;
        final String sourcePath;
        CompiledPrompt(PromptMeta meta, Template template, String sourcePath) {
            this.meta = meta; this.template = template; this.sourcePath = sourcePath;
        }
    }

    /**
     * Prompt 元数据（对应 YAML frontmatter）
     */
    public static class PromptMeta {
        private int version;
        private String modified;
        private String author;
        private String changes;
        private String model;

        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
        public String getModified() { return modified; }
        public void setModified(String modified) { this.modified = modified; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getChanges() { return changes; }
        public void setChanges(String changes) { this.changes = changes; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/PromptManager.java
git commit -m "feat(prompt): add PromptManager - startup loading, Mustache rendering, bilingual support"
```

---

### Task 2.4: 创建所有中文 Prompt 文件（zh/）

**Files:**
- Create: 19 个 `src/main/resources/prompts/zh/**/*.md` 文件

- [ ] **Step 1: 创建目录结构并写入所有 19 个 Prompt 文件**

需要创建的目录：
```
src/main/resources/prompts/zh/chat/
src/main/resources/prompts/zh/aiops/
src/main/resources/prompts/zh/memory/
src/main/resources/prompts/zh/summary/
src/main/resources/prompts/zh/rewrite/
src/main/resources/prompts/zh/intent/
src/main/resources/prompts/zh/eval/
src/main/resources/prompts/zh/agentic-rag/
```

**文件 1: `prompts/zh/chat/system-prompt.md`**

```markdown
---
version: 1
modified: 2026-08-01
author: chief
changes: "从 ChatService.buildSystemPrompt() 提取"
model: chat
---

你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。
当用户询问时间相关问题时，使用 getCurrentDateTime 工具。
当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。
当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。
当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。

{{#agenticRagEnabled}}
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
- 有 ≥1 条结果相关性 ≥ {{minRelevanceScore}}
- _meta.remainingRounds == 0
- 同一 query 连续 2 次评估 recommendation 仍为 REFINE

### 生成阶段
- 综合所有达标结果生成答案，注明信息来源
- 如果确实无相关信息，如实告知用户，不要编造
- 严禁无限检索！remainingRounds 为 0 时必须基于已有最好结果强制回答
{{/agenticRagEnabled}}

{{#memoryProfileBlock}}
{{memoryProfileBlock}}
{{/memoryProfileBlock}}

{{#historyBlock}}
--- 对话历史 ---
{{historyBlock}}
--- 对话历史结束 ---

{{/historyBlock}}
{{#summaryBlock}}
--- 对话历史摘要 ---
以下是此前对话的摘要：
{{summaryBlock}}
--- 对话历史摘要结束 ---

请基于以上对话历史摘要，回答用户的新问题。
{{/summaryBlock}}
{{^summaryBlock}}
{{#historyBlock}}
请基于以上对话历史，回答用户的新问题。
{{/historyBlock}}
{{/summaryBlock}}
```

**文件 2-19:** 按相同格式创建。关键要点：
- 每个文件从当前 Java 代码中提取内联 Prompt 字符串
- 将变量部分替换为 `{{variableName}}` Mustache 占位符
- 添加正确的 YAML frontmatter

**文件 2: `prompts/zh/aiops/supervisor-prompt.md`**
```markdown
---
version: 1
modified: 2026-08-01
author: chief
changes: "从 ReactAgentRunner.buildSupervisorSystemPrompt() 提取"
model: aiops.supervisor
---

你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。
```

**文件 3: `prompts/zh/aiops/planner-prompt.md`**（从 ReactAgentRunner.buildPlannerPrompt() 提取完整内容）
**文件 4: `prompts/zh/aiops/executor-prompt.md`**（从 ReactAgentRunner.buildExecutorPrompt() 提取）
**文件 5: `prompts/zh/aiops/task-prompt.md`** — 简短的任务描述
**文件 6: `prompts/zh/aiops/fallback-report.md`**（从 generateFallbackReport 提取模板，`%s` → `{{taskPrompt}}`）
**文件 7: `prompts/zh/memory/extraction-prompt.md`**（从 MemoryExtractor.buildExtractionPrompt() 提取，`%s` → `{{existingMemories}}` 和 `{{conversation}}`）
**文件 8: `prompts/zh/memory/conflict-resolution.md`**（从 resolveConflict 提取）
**文件 9: `prompts/zh/memory/merge-prompt.md`**（从 resolveMerge 提取）
**文件 10: `prompts/zh/summary/summary-prompt.md`**（从 SummaryGenerator.buildSummaryPrompt() 提取）
**文件 11: `prompts/zh/rewrite/prompt-rewrite.md`**（从 PromptRewriteStrategy 提取，`%s` → `{{originalQuery}}`）
**文件 12: `prompts/zh/rewrite/hypothetical-answer.md`**（从 HypotheticalAnswerStrategy 提取）
**文件 13: `prompts/zh/rewrite/detail-abstract.md`**（从 DetailAbstractStrategy 提取）
**文件 14: `prompts/zh/intent/classification-prompt.md`**（从 IntentRouter.buildClassificationPrompt() 提取）
**文件 15: `prompts/zh/eval/judge-prompt.md`**（从 AIOpsEvaluator.buildJudgePrompt() 提取）
**文件 16: `prompts/zh/agentic-rag/decompose-question.md`**（从 DecomposeQuestionTool 提取）
**文件 17: `prompts/zh/agentic-rag/evaluate-results.md`**（从 EvaluateSearchResultsTool 提取）
**文件 18: `prompts/zh/agentic-rag/search-capabilities.md`**（从 GetSearchCapabilitiesTool 提取）

- [ ] **Step 2: 编译验证（确保 Prompt 文件被打包到 classpath）**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS, `prompts/` 目录下的 `.md` 文件出现在 `target/classes/prompts/` 中。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/prompts/
git commit -m "feat(prompt): add all 19 Chinese prompt files with YAML frontmatter and Mustache templates"
```

---

### Task 2.5: 修改 ChatService 使用 PromptManager

**Files:**
- Modify: `src/main/java/org/example/service/ChatService.java`

- [ ] **Step 1: 修改 ChatService**

```java
// 新增注入
@Autowired
private PromptManager promptManager;

// 修改 buildSystemPrompt 方法签名和实现
public String buildSystemPrompt(List<Map<String, String>> history, String summary, String userId, String lang) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("agenticRagEnabled", agenticRagEnabled);
    vars.put("minRelevanceScore", String.format("%.1f", agenticMinRelevanceScore));

    // 记忆注入
    String memoryBlock = "";
    if (memoryEnabled && memoryManager != null && memoryProperties != null && userId != null && !userId.isEmpty()) {
        memoryBlock = buildMemoryProfileBlock(userId);
    }
    vars.put("memoryProfileBlock", memoryBlock);

    // 摘要模式 vs 详情模式
    if (summary != null && !summary.isEmpty()) {
        vars.put("summaryBlock", summary);
        vars.put("historyBlock", "");
    } else if (history != null && !history.isEmpty()) {
        vars.put("summaryBlock", "");
        vars.put("historyBlock", buildHistoryText(history));
    } else {
        vars.put("summaryBlock", "");
        vars.put("historyBlock", "");
    }

    return promptManager.render("chat/system-prompt", vars, lang);
}

// 新增辅助方法
private String buildHistoryText(List<Map<String, String>> history) {
    StringBuilder sb = new StringBuilder();
    for (Map<String, String> msg : history) {
        String role = msg.get("role");
        String content = msg.get("content");
        if ("user".equals(role)) {
            sb.append("用户: ").append(content).append("\n");
        } else if ("assistant".equals(role)) {
            sb.append("助手: ").append(content).append("\n");
        }
    }
    return sb.toString();
}

// 保留旧签名作为兼容（内部调用新方法，lang 默认 zh）
public String buildSystemPrompt(List<Map<String, String>> history, String summary, String userId) {
    return buildSystemPrompt(history, summary, userId, "zh");
}

public String buildSystemPrompt(List<Map<String, String>> history) {
    return buildSystemPrompt(history, null, null, "zh");
}

// 删除 buildAgenticRagInstructions() 方法（内容已移至 Prompt 文件）
// 删除 buildMemoryProfileBlock() 中注入逻辑的重复（简化）

// 添加 import
import org.example.service.PromptManager;
import java.util.HashMap;
```

- [ ] **Step 2: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/ChatService.java
git commit -m "refactor(chat): use PromptManager for system prompt rendering in ChatService"
```

---

### Task 2.6: 修改 ReactAgentRunner 使用 PromptManager

**Files:**
- Modify: `src/main/java/org/example/agent/ReactAgentRunner.java`

- [ ] **Step 1: 注入 PromptManager 并修改所有 buildXxxPrompt 方法**

```java
// 新增注入
@Autowired
private PromptManager promptManager;

// 修改 buildPlannerPrompt（删除原 ~100 行内联字符串，替换为:）
private String buildPlannerPrompt() {
    return promptManager.render("aiops/planner-prompt", Map.of(), "zh");
}

// 修改 buildExecutorPrompt
private String buildExecutorPrompt() {
    return promptManager.render("aiops/executor-prompt", Map.of(), "zh");
}

// 修改 buildSupervisorSystemPrompt
private String buildSupervisorSystemPrompt() {
    return promptManager.render("aiops/supervisor-prompt", Map.of(), "zh");
}

// 删除原来的 buildPlannerPrompt / buildExecutorPrompt / buildSupervisorSystemPrompt 方法体中的内联字符串

// 修改 executeOrchestration（使用 PromptManager 加载 task-prompt）
@Override
public AiOpsResult executeOrchestration(String taskPrompt) {
    // ... 前面不变
    // 旧: String fullTaskPrompt = "你是企业级 SRE，接到了自动化告警排查任务..."
    // 新: 
    String fullTaskPrompt = promptManager.render("aiops/task-prompt", Map.of(), "zh");
    // ... 后续不变
}

// 修改 generateFallbackReport
private AiOpsResult generateFallbackReport(String taskPrompt) {
    try {
        String forcePrompt = promptManager.render("aiops/fallback-report",
                Map.of("taskPrompt", taskPrompt), "zh");
        // ... 其余不变
    }
}
```

- [ ] **Step 2: 删除 executeOrchestration 中的重复 task prompt 字符串**

`AiOpsService` 中的重复 task prompt 也在下一步处理。

- [ ] **Step 3: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/agent/ReactAgentRunner.java
git commit -m "refactor(agent): use PromptManager for all AIOps prompts, remove inline prompt strings"
```

---

### Task 2.7: 修改 AiOpsService 消除重复 Prompt

**Files:**
- Modify: `src/main/java/org/example/service/AiOpsService.java`

- [ ] **Step 1: 修改 AiOpsService**

```java
// 新增注入
@Autowired
private PromptManager promptManager;

// 修改 executeAiOpsAnalysis（约第31行）
public AiOpsResult executeAiOpsAnalysis() {
    logger.info("开始执行 AI Ops 多 Agent 协作流程");
    // 旧: String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务...";
    // 新: 从 PromptManager 加载，消除与 ReactAgentRunner 的重复
    String taskPrompt = promptManager.render("aiops/task-prompt", Map.of(), "zh");
    return agentRunner.executeOrchestration(taskPrompt);
}

// 添加 import
import org.example.service.PromptManager;
import java.util.Map;
```

- [ ] **Step 2: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/service/AiOpsService.java
git commit -m "fix(aiops): remove duplicate task prompt, use PromptManager"
```

---

### Task 2.8: 修改剩余服务使用 PromptManager

**Files:**
- Modify: `src/main/java/org/example/service/MemoryExtractor.java`
- Modify: `src/main/java/org/example/service/SummaryGenerator.java`
- Modify: `src/main/java/org/example/service/rewrite/PromptRewriteStrategy.java`
- Modify: `src/main/java/org/example/service/rewrite/HypotheticalAnswerStrategy.java`
- Modify: `src/main/java/org/example/service/rewrite/DetailAbstractStrategy.java`
- Modify: `src/main/java/org/example/agent/router/IntentRouter.java`
- Modify: `src/main/java/org/example/agent/eval/AIOpsEvaluator.java`
- Modify: `src/main/java/org/example/agent/tool/DecomposeQuestionTool.java`
- Modify: `src/main/java/org/example/agent/tool/EvaluateSearchResultsTool.java`
- Modify: `src/main/java/org/example/agent/tool/GetSearchCapabilitiesTool.java`

- [ ] **Step 1: 修改 MemoryExtractor — buildExtractionPrompt, resolveConflict, resolveMerge**

```java
// 注入 PromptManager
@Autowired
private PromptManager promptManager;

// buildExtractionPrompt 改为:
private String buildExtractionPrompt(String existingMemories, String conversation) {
    return promptManager.render("memory/extraction-prompt",
            Map.of("existingMemories",
                    existingMemories.isEmpty() ? "（无已有记忆）" : existingMemories,
                   "conversation", conversation),
            "zh");
}

// resolveConflict 中的 prompt 构建改为:
String prompt = promptManager.render("memory/conflict-resolution",
        Map.of("oldContent", oldContent, "oldConf", String.format("%.0f%%", oldConf * 100),
               "newContent", newContent, "newConf", String.format("%.0f%%", newConf * 100)),
        "zh");

// resolveMerge 中的 prompt 构建改为:
String prompt = promptManager.render("memory/merge-prompt",
        Map.of("oldContent", oldContent, "newContent", newContent), "zh");
```

- [ ] **Step 2: 修改 SummaryGenerator — buildSummaryPrompt**

```java
// 注入 PromptManager
@Autowired
private PromptManager promptManager;

// buildSummaryPrompt 改为:
private String buildSummaryPrompt(String historyText, int maxLen) {
    return promptManager.render("summary/summary-prompt",
            Map.of("maxLen", String.valueOf(maxLen), "historyText", historyText),
            "zh");
}
```

- [ ] **Step 3: 修改 3 个 QueryRewriteStrategy**

```java
// PromptRewriteStrategy — 注入 PromptManager，在 rewrite() 中:
// 旧: String.format(REWRITE_PROMPT_TEMPLATE, originalQuery)
// 新: promptManager.render("rewrite/prompt-rewrite", Map.of("originalQuery", originalQuery), "zh")
// 删除 REWRITE_PROMPT_TEMPLATE 常量

// 同理修改 HypotheticalAnswerStrategy 和 DetailAbstractStrategy
// 这些策略类需要注入 PromptManager 或通过构造函数传入
// 由于这些类由 QueryRewriteService 初始化（非 Spring Bean），
// 应在 QueryRewriteService.init() 中传入 promptManager
```

**QueryRewriteService 修改：**
```java
// 新增注入
private final PromptManager promptManager;

// 构造函数新增参数
public QueryRewriteService(QueryRewriteProperties properties,
                           StringRedisTemplate redisTemplate,
                           @Value("${dashscope.api.key}") String dashscopeApiKey,
                           ModelProperties modelProperties,
                           PromptManager promptManager) {
    // ... 已有赋值
    this.promptManager = promptManager;
}

// init() 中传入 promptManager:
case PROMPT_REWRITE -> {
    yield new PromptRewriteStrategy(createRewriteChatModel(), promptManager);
}
case HYPOTHETICAL_ANSWER -> {
    yield new HypotheticalAnswerStrategy(createRewriteChatModel(), promptManager);
}
case DETAIL_ABSTRACT -> {
    yield new DetailAbstractStrategy(createRewriteChatModel(), promptManager);
}
```

**PromptRewriteStrategy 修改：**
```java
// 新增字段
private final PromptManager promptManager;

// 修改构造函数
public PromptRewriteStrategy(ChatModel chatModel, PromptManager promptManager) {
    this.chatModel = chatModel;
    this.promptManager = promptManager;
}

// rewrite() 中:
String prompt = promptManager.render("rewrite/prompt-rewrite",
        Map.of("originalQuery", originalQuery), "zh");
// 删除 REWRITE_PROMPT_TEMPLATE 常量
```

同样方式修改 `HypotheticalAnswerStrategy` 和 `DetailAbstractStrategy`。

- [ ] **Step 4: 修改 IntentRouter — buildClassificationPrompt**

```java
// 注入 PromptManager
@Autowired
private PromptManager promptManager;

// buildClassificationPrompt 改为调用 PromptManager
private String buildClassificationPrompt(String userInput) {
    return promptManager.render("intent/classification-prompt",
            Map.of("userInput", userInput), "zh");
}
```

- [ ] **Step 5: 修改 AIOpsEvaluator — buildJudgePrompt**

```java
// 注入 PromptManager
@Autowired
private PromptManager promptManager;

// buildJudgePrompt 改为调用 PromptManager
private String buildJudgePrompt(TestCaseMeta meta, String reportText) {
    boolean hasMeta = meta != null;
    String truncatedReport = reportText.length() > 4000
            ? reportText.substring(0, 4000) + "\n... (truncated)"
            : reportText;

    Map<String, Object> vars = new HashMap<>();
    vars.put("hasMeta", hasMeta);
    vars.put("expectedRootCauses", hasMeta ? String.join(",", meta.getExpectedRootCauses()) : "");
    vars.put("criticalEvidence", hasMeta ? String.join(",", meta.getCriticalEvidence()) : "");
    vars.put("reportText", truncatedReport);
    return promptManager.render("eval/judge-prompt", vars, "zh");
}
```

- [ ] **Step 6: 修改 3 个 AgenticRAG 工具类**

`DecomposeQuestionTool`、`EvaluateSearchResultsTool`、`GetSearchCapabilitiesTool` 类似修改，注入 `PromptManager`，用 `promptManager.render(...)` 替代内联 Prompt。

- [ ] **Step 7: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: migrate all remaining inline prompts to PromptManager"
```

---

### Task 2.8b: 英文 Prompt 文件（后续翻译任务）

> **注意**：英文 Prompt 文件（`prompts/en/` 目录下的 19 个文件）暂不在本次实施范围内创建。PromptManager 架构已支持双语切换（`lang` 参数），`en/` 目录下的文件结构需与 `zh/` 一致。英文翻译涉及专业术语（AIOps、SRE）的人工审校，建议在中文 Prompt 稳定运行后由运维团队统一翻译。目前英文请求会自动回退到中文 Prompt。

---

### Task 2.9: 阶段2 集成测试与验证

- [ ] **Step 1: 完整编译**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动验证**

```bash
cd SuperBizAgent-release-2026-05-17 && timeout 30 mvn spring-boot:run 2>&1 | grep -E "Prompt|启动|ERROR|WARN"
```

Expected: 日志中出现 "Prompt 加载完成，共 38 个模板"（19 中文 + 19 英文，如果英文还没创建则 19 个）

- [ ] **Step 3: Commit 阶段2 整体**

```bash
git commit -m "chore: Phase 2 complete - all prompts externalized to Mustache templates"
```

---

## 阶段3：模块12 — API 版本化

### Task 3.1: 添加 SpringDoc OpenAPI 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 添加 SpringDoc 依赖**

```xml
<!-- SpringDoc OpenAPI - API 文档自动生成 -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

- [ ] **Step 2: 添加 springdoc 配置到 application.yml**

```yaml
# SpringDoc OpenAPI 配置
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

- [ ] **Step 3: dev profile 启用，prod profile 禁用**

`application-dev.yml`:
```yaml
springdoc:
  swagger-ui:
    enabled: true
```

`application-prod.yml`:
```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

- [ ] **Step 4: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-prod.yml
git commit -m "build: add SpringDoc OpenAPI dependency and configuration"
```

---

### Task 3.2: DTO 独立化

**Files:**
- Create: `src/main/java/org/example/dto/ChatRequest.java`
- Create: `src/main/java/org/example/dto/ClearRequest.java`
- Create: `src/main/java/org/example/dto/SessionInfoResponse.java`
- Create: `src/main/java/org/example/dto/ChatResponse.java`
- Create: `src/main/java/org/example/dto/LoginRequest.java`
- Create: `src/main/java/org/example/dto/LoginResult.java`
- Modify: `src/main/java/org/example/controller/ChatController.java`（删除内嵌类，改用独立 DTO）
- Modify: `src/main/java/org/example/controller/AuthController.java`（同上）

- [ ] **Step 1: 创建独立 DTO 文件（带 @Schema 注解）**

**ChatRequest.java:**
```java
package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "聊天请求")
public class ChatRequest {
    @Schema(description = "会话ID", example = "abc123-def456")
    @JsonProperty("Id")
    @JsonAlias({"id", "ID"})
    private String Id;

    @Schema(description = "用户问题", example = "当前系统有哪些活跃告警？")
    @JsonProperty("Question")
    @JsonAlias({"question", "QUESTION"})
    @NotBlank(message = "问题内容不能为空")
    private String Question;

    public String getId() { return Id; }
    public void setId(String Id) { this.Id = Id; }
    public String getQuestion() { return Question; }
    public void setQuestion(String Question) { this.Question = Question; }
}
```

**ChatResponse.java:**
```java
package org.example.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "聊天响应")
public class ChatResponse {
    @Schema(description = "是否成功")
    private boolean success;
    @Schema(description = "AI 回答内容")
    private String answer;
    @Schema(description = "错误信息（仅失败时）")
    private String errorMessage;
    @Schema(description = "会话ID")
    private String sessionId;

    public static ChatResponse success(String answer, String sessionId) {
        ChatResponse r = new ChatResponse();
        r.success = true; r.answer = answer; r.sessionId = sessionId;
        return r;
    }

    public static ChatResponse error(String errorMessage) {
        ChatResponse r = new ChatResponse();
        r.success = false; r.errorMessage = errorMessage;
        return r;
    }

    // getters and setters ...
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
```

类似地创建 `ClearRequest.java`、`SessionInfoResponse.java`、`LoginRequest.java`、`LoginResult.java`。

- [ ] **Step 2: 修改 ChatController 和 AuthController 删除内嵌类，改用独立 DTO**

删除 `ChatController` 中的 `ChatRequest`、`ClearRequest`、`SessionInfoResponse`、`ChatResponse` 内嵌类。
删除 `AuthController` 中的 `LoginRequest`、`LoginResult` 内嵌类。
修改各方法的引用路径。

- [ ] **Step 3: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/dto/
git add src/main/java/org/example/controller/ChatController.java
git add src/main/java/org/example/controller/AuthController.java
git commit -m "refactor(dto): extract inner DTO classes to standalone files with @Schema annotations"
```

---

### Task 3.3: 创建 V1 Controller（带 OpenAPI 注解）

**Files:**
- Create: `src/main/java/org/example/controller/v1/ChatV1Controller.java`
- Create: `src/main/java/org/example/controller/v1/AIOpsV1Controller.java`
- Create: `src/main/java/org/example/controller/v1/MemoryV1Controller.java`
- Create: `src/main/java/org/example/controller/v1/AuthV1Controller.java`
- Create: `src/main/java/org/example/controller/v1/UploadV1Controller.java`
- Create: `src/main/java/org/example/controller/v1/HealthV1Controller.java`

- [ ] **Step 1: 创建 ChatV1Controller**

将当前 `ChatController` 的对话相关逻辑迁移到 `ChatV1Controller`，路径改为 `/api/v1`，添加 `@Tag`、`@Operation` 注解。

```java
package org.example.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.agent.AgentRunner;
import org.example.agent.router.IntentCategory;
import org.example.agent.router.IntentResult;
import org.example.agent.router.IntentRouter;
import org.example.agent.tool.RecallMemoryTool;
import org.example.dto.*;
import org.example.exception.InvalidInputException;
import org.example.exception.ResourceNotFoundException;
import org.example.service.ChatService;
import org.example.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;

@Tag(name = "聊天对话", description = "对话交互与流式响应接口")
@RestController
@RequestMapping("/api/v1")
public class ChatV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(ChatV1Controller.class);

    @Autowired private ChatService chatService;
    @Autowired private SessionManager sessionManager;
    @Autowired private AgentRunner agentRunner;
    @Autowired private IntentRouter intentRouter;

    @Operation(summary = "普通对话", description = "发送消息并获取 AI 回复，支持自动工具调用")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "对话成功",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @ApiResponse(responseCode = "400", description = "参数校验失败"),
        @ApiResponse(responseCode = "429", description = "请求频率超限"),
        @ApiResponse(responseCode = "503", description = "LLM 服务暂不可用")
    })
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody @Valid ChatRequest request) {
        // ... 迁移 ChatController.chat() 的逻辑，路径从 /api 改为 /api/v1
        // 内容与原有逻辑相同，不再重复
    }

    @Operation(summary = "流式对话", description = "SSE 流式输出 AI 回复，支持实时工具调用状态反馈")
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody @Valid ChatRequest request) {
        // ... 迁移 ChatController.chatStream() 的逻辑
    }

    @Operation(summary = "清空会话", description = "清除指定会话的全部对话历史")
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        // ... 迁移 ChatController.clearChatHistory() 的逻辑
    }

    @Operation(summary = "查询会话信息", description = "获取指定会话的历史消息对数和元数据")
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        // ... 迁移 ChatController.getSessionInfo() 的逻辑
    }

    // 辅助方法 getCurrentUserId() 从 ChatController 复制
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        return "anonymous";
    }
}
```

- [ ] **Step 2: 创建 AIOpsV1Controller**

将 AIOps 相关的 `/ai_ops` 端点和 `handleAIOpsRoute` / `handleAIOpsRouteStream` 逻辑迁移到 `AIOpsV1Controller`。

```java
@Tag(name = "智能运维", description = "AIOps 告警自动分析与诊断")
@RestController
@RequestMapping("/api/v1")
public class AIOpsV1Controller { ... }
```

- [ ] **Step 3: 创建 MemoryV1Controller、AuthV1Controller、UploadV1Controller、HealthV1Controller**

分别从现有的 `MemoryController`、`AuthController`、`FileUploadController`、`MilvusCheckController` 迁移逻辑，路径改为 `/api/v1`。

`HealthV1Controller` 新增 `/health` 端点做综合健康检查（检查 Redis、Milvus 连通性）。

- [ ] **Step 4: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS（此时旧 Controller 仍存在，会与 V1 Controller 冲突——注意类名不同、路径不同，应该可以共存）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/controller/v1/
git commit -m "feat(api): create V1 controllers with OpenAPI annotations under /api/v1"
```

---

### Task 3.4: 创建 Legacy Controller（301 重定向）

**Files:**
- Create: `src/main/java/org/example/controller/legacy/ChatLegacyController.java`
- Create: `src/main/java/org/example/controller/legacy/AIOpsLegacyController.java`
- Create: `src/main/java/org/example/controller/legacy/MemoryLegacyController.java`
- Create: `src/main/java/org/example/controller/legacy/AuthLegacyController.java`
- Create: `src/main/java/org/example/controller/legacy/UploadLegacyController.java`
- Create: `src/main/java/org/example/controller/legacy/MilvusLegacyController.java`

- [ ] **Step 1: 创建所有 Legacy Controller**

每个 Legacy Controller 匹配旧路径，返回 301 重定向：

```java
package org.example.controller.legacy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatLegacyController {

    @PostMapping("/chat")
    public ResponseEntity<Void> chat() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat").build();
    }

    @PostMapping("/chat_stream")
    public ResponseEntity<Void> chatStream() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat_stream").build();
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<Void> clearChatHistory() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat/clear").build();
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<Void> getSessionInfo(@PathVariable String sessionId) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/api/v1/chat/session/" + sessionId).build();
    }
}
```

`MilvusLegacyController` 使用 `@RequestMapping("/milvus")`。

类似创建其他 Legacy Controller。

- [ ] **Step 2: 删除旧 Controller 文件**

删除：`ChatController.java`、`AuthController.java`、`MemoryController.java`、`FileUploadController.java`、`MilvusCheckController.java`

- [ ] **Step 3: 编译验证**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/controller/legacy/
git rm src/main/java/org/example/controller/ChatController.java
git rm src/main/java/org/example/controller/AuthController.java
git rm src/main/java/org/example/controller/MemoryController.java
git rm src/main/java/org/example/controller/FileUploadController.java
git rm src/main/java/org/example/controller/MilvusCheckController.java
git commit -m "feat(api): add legacy controllers with 301 redirects, remove old controllers"
```

---

### Task 3.5: 创建 SSE 协议文档

**Files:**
- Create: `docs/api-sse-protocol.md`

- [ ] **Step 1: 创建 docs/api-sse-protocol.md**

```markdown
# SSE 流式协议规范 v1

> 版本: 1.0 | 日期: 2026-08-01

## 连接信息

- **端点**: `POST /api/v1/chat_stream`
- **Content-Type**: `application/json`
- **Accept**: `text/event-stream`
- **超时**: 5 分钟（300 秒）

## 事件类型

| SSE event | 说明 | 数据格式 |
|-----------|------|----------|
| `message` | 通用消息容器 | `AgentEvent` JSON |
| `content` | 文本增量 | `{"type":"CONTENT_CHUNK","data":"文本片段"}` |
| `tool_start` | 工具调用开始 | `{"type":"TOOL_CALL_START","data":"工具名称"}` |
| `tool_end` | 工具调用结束 | `{"type":"TOOL_CALL_END","data":"结果摘要"}` |
| `error` | 错误事件 | `{"type":"ERROR","data":"错误描述"}` |
| `done` | 流完成 | `{"type":"DONE","data":null,"sessionId":"会话ID"}` |

## AgentEvent 数据结构

```json
{
  "type": "CONTENT_CHUNK | TOOL_CALL_START | TOOL_CALL_END | ERROR | DONE",
  "data": "具体数据内容",
  "sessionId": "会话ID（仅 DONE 事件携带）"
}
```

## 完整交互示例

### 请求
```http
POST /api/v1/chat_stream HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{"Id":"session-abc","Question":"帮我查一下CPU告警"}
```

### 响应流
```
event:message
data:{"type":"CONTENT_CHUNK","data":"正在"}

event:message
data:{"type":"CONTENT_CHUNK","data":"查询"}

event:message
data:{"type":"TOOL_CALL_START","data":"queryPrometheusAlerts"}

event:message
data:{"type":"TOOL_CALL_END","data":"{\"status\":\"success\"}"}

event:message
data:{"type":"CONTENT_CHUNK","data":"当前没有活跃的CPU告警。"}

event:message
data:{"type":"DONE","data":null,"sessionId":"session-abc"}
```

## AIOps SSE 端点

- **端点**: `POST /api/v1/ai_ops`
- **超时**: 10 分钟
- **事件格式**: 同上，但内容为告警分析报告的逐块输出
```

- [ ] **Step 2: Commit**

```bash
git add docs/api-sse-protocol.md
git commit -m "docs: add SSE protocol specification"
```

---

### Task 3.6: 阶段3 集成测试与验证

- [ ] **Step 1: 完整编译**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动验证**

```bash
cd SuperBizAgent-release-2026-05-17 && timeout 30 mvn spring-boot:run 2>&1 | grep -E "Started|ERROR|v1"
```

Expected: 应用正常启动，无 ERROR

- [ ] **Step 3: 验证 Swagger UI 可访问**

启动应用后访问 `http://localhost:9900/api/v1/docs/ui`，确认 Swagger UI 页面正常显示。

- [ ] **Step 4: 验证 301 重定向**

```bash
# 测试旧路径重定向
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:9900/api/chat -H "Content-Type: application/json" -d '{"Id":"test","Question":"hello"}'
```

Expected: `301`（或跟随重定向后得到 `200`）

- [ ] **Step 5: 验证新路径正常工作**

```bash
curl -s -X POST http://localhost:9900/api/v1/chat -H "Content-Type: application/json" -H "X-API-Key: dev-api-key-change-me" -d '{"Id":"test","Question":"hello"}'
```

Expected: 返回正常的 JSON 响应

- [ ] **Step 6: Commit 阶段3 整体**

```bash
git commit -m "chore: Phase 3 complete - API versioning with /api/v1, SpringDoc OpenAPI, SSE protocol docs"
```

---

## 最终验证

所有三个阶段完成后：

- [ ] **完整构建**: `mvn clean compile` — BUILD SUCCESS
- [ ] **启动验证**: `mvn spring-boot:run` — 正常启动，无异常
- [ ] **Swagger UI**: `http://localhost:9900/api/v1/docs/ui` 可访问
- [ ] **旧路径重定向**: `/api/chat` → 301 → `/api/v1/chat`
- [ ] **新路径可用**: `/api/v1/chat` 正常返回
- [ ] **配置校验**: 强行配错模型参数值 → 启动失败
- [ ] **Prompt 加载**: 启动日志显示 "Prompt 加载完成"
