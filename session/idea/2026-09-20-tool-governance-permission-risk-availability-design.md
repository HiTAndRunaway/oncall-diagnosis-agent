# Tool Routing 与工具治理设计

> 日期：2026-09-20（设计讨论日）
> 状态：**部分实现**（阶段一 P0 已完成，其余未实现）
> 范围：`org.example.agent.tool` 下全部 `@Tool` 工具，以及经 `ToolCallbackProvider` 注入的 MCP 工具
> 说明：本文档由一次完整设计讨论收敛而成，含概念梳理、方案评审、完整设计与实施计划。

## 实施状态

| 阶段 | 状态 | 落地位置 |
|---|---|---|
| **阶段一 P0：修身份越权** | ✅ **已于 2026-09-21 完成** | 分支 `fix/tool-identity-security-context`；新增 `org.example.security.CurrentUser`；`RecallMemoryTool` 与 `ForgetMemoryTool` 改为执行时读取认证上下文 |
| 阶段二 P1：契约 + 权限 + 审计 | ⬜ 未实现 | — |
| 阶段三补充 P1.5：修工具描述语义重叠 | ⬜ 未实现 | — |
| 阶段三 P2：可用性探针 + 风险治理 | ⬜ 未实现 | — |
| 阶段四 P3：动态候选集 | ⬜ 未实现（前置条件未满足） | — |

阶段一的实现要点与偏离记录：

1. **范围扩大：顺带修掉一处更严重的同类越权。** 独立代码审查发现 `ForgetMemoryTool.forgetMemory` 把 `userId` 声明为 **LLM 可填的 `@ToolParam`**，并将它直接传给 `memoryManager.deleteMemory(userId, ...)` —— 模型（或经检索文档注入的指令）可指定任意用户，进而**删除他人记忆**。这是攻击者可控的破坏性操作，比原 `ThreadLocal` 问题更严重（原问题最多读到他人身份，这里是可指定）。已移除该参数并改用 `CurrentUser.getRequiredId()`，未认证时拒绝。
2. **额外消除了一处重复**：`ChatV1Controller` 与 `MemoryV1Controller` 各有一份完全相同的私有 `getCurrentUserId()`，一并收敛到 `CurrentUser.getId()`。
3. **额外清理了死代码**：`ChatService` 中注入但从未使用的 `RecallMemoryTool` 与 `ForgetMemoryTool` 字段（Agent 创建逻辑早已迁至 `ReactAgentRunner`）。
4. **行为差异（有意保留，但须注意）**：未认证时 `recallMemory` / `forgetMemory` 由「操作 `"anonymous"` 共享记忆桶」改为**拒绝执行**并返回未认证提示。在默认配置 `superbiz.security.enabled=false` 下不存在已认证身份，因此这两个工具在默认配置下不可用 —— 这是失败安全的取舍，已写入 README 行为变更，若需免认证单用户使用须配置 API Key。
5. **已知取舍**：用户名字面为 `"anonymous"` 时会被 `isAuthenticated()` 判为未认证而拒绝。已用测试显式锁定该行为。
6. **顺带修掉两处同源缺陷（审查报告 #4/#6）**：
   - `MemoryV1Controller` 的变更类端点（`DELETE /{memoryId}`、`DELETE /clear`）原先也用 `getId()`，未认证时操作 `"anonymous"` 共享记忆桶 —— 默认配置下任何网络客户端都能清空它。现改用 `getRequiredId()`；并新增 `GlobalExceptionHandler` 对 `IllegalStateException` 的 401 映射（原先会落到兜底 500），同时让两个端点的 `catch (Exception)` 不再把 401 吞成 503。
   - `AgenticRagGuard`：`reset()` 改为在 `ReactAgentRunner.execute` 与 `executeStream` **两条路径**都调用（原先流式路径从不重置，SSE 会继承上一次请求的轮次与起始时间），新增 `clear()` 并在同步路径的 finally 中配对调用。流式路径**故意不调用 `clear()`**：流的订阅/回调可能落在其他线程，在那里清理会清错线程的状态。同步路径另补上 `buildReactAgent` 的异常保护（原先构建失败会抛原始异常而非 `LlmServiceException`）。
7. **验证方式**：全量 **87 测试通过**；并用**变异测试**（临时把身份改回静态缓存）确认护栏能捕获回归 —— 3 个用例如期失败（含新增的确定性残留用例），证明测试非装饰品。
8. **启动验证发现并修复了一个更严重的缺陷（审查报告「无法验证」项之一）**：审查提出「`SecurityConfig` 为 `enabled=true` 条件注册、无其他 `SecurityFilterChain` bean，Boot 默认安全链可能接管」这一疑点。补充 `SecurityFilterChainStartupTest`（随机端口 + 真实 HTTP）后**证实该疑点成立且后果严重**：
   - 首次运行：`GET /api/v1/memory/panel` 与 `DELETE /api/v1/memory/*` 全部返回 **302 FOUND**（重定向到 Boot 默认登录页）→ **`security.enabled=false`（默认）时整个 HTTP API 不可用**，与 `application.yml` 注释「false=放行所有请求」完全相反。
   - 修复：`SecurityConfig` 取消类级条件注解，改为两条链按开关二选一 —— 关闭时显式注册 `permitAllSecurityFilterChain`（`matchIfMissing=true`，确保任何情况下恰好一条链生效）。
   - 修复后：`GET /api/v1/memory/panel` → **200 OK**；`DELETE` → **401**（验证 401 映射与 503 吞噬修复均生效）。
9. **新增 `memory.require-authenticated` 开关（审查报告 #3）**：默认 `true`（未认证拒绝，安全默认）；设 `false` 时未认证调用退化为共享 `"anonymous"` 桶，即改造前的行为，供单用户本地开发使用，并在日志中告警提示串扰风险。REST 层记忆删除接口**不受该开关影响**，始终要求认证。
10. **框架侧核实（仍未做运行时验证）**：审查指出框架存在**异步/并行工具派发**路径（`AgentToolNode.executeToolCallsParallel` → `AsyncToolCallbackAdapter.wrapIfNeeded` → `CompletableFuture.supplyAsync(executor)`）。经反编译核对该框架 jar 确认：该路径由 `parallelToolExecution` / `wrapSyncToolsAsAsync` 控制，**`ReactAgent` 未暴露对应 builder 方法、本项目也未启用**，默认内联执行；且框架内**不存在**任何 `SecurityContext` 传播机制（无 `DelegatingSecurityContext*`）。因此当前配置下请求线程上的 `SecurityContextHolder` 对工具可用。
    > ⚠️ **但这一点仍未经运行时验证**（需要真实 LLM 调用才能跑通 Agent 工具链路）。若将来启用并行工具执行，或接入异步 MCP 工具，必须同时引入上下文传播，否则工具会退化为匿名并被拒绝。

---

## 目录

- [第一部分 概念与边界](#第一部分-概念与边界)
- [第二部分 现状盘点](#第二部分-现状盘点)
- [第三部分 分层 Routing 方案评审](#第三部分-分层-routing-方案评审)
- [第四部分 工具治理设计](#第四部分-工具治理设计)
- [第五部分 实施计划](#第五部分-实施计划)
- [第六部分 验收标准与风险](#第六部分-验收标准与风险)
- [第七部分 待确认问题](#第七部分-待确认问题)

---

# 第一部分 概念与边界

## 1.1 Tool Routing 是什么

**在「给定上下文 + 一批候选工具」的条件下，决定调用哪个工具、传什么参数、调用几次。**

它不是某一个函数，而是一段决策逻辑。按决策者分三种形态：

| 形态 | 谁做决策 | 特点 |
|---|---|---|
| LLM 自主选择（function calling / ReAct） | 模型读工具描述后决定 | 灵活；工具多了会选错、上下文被 schema 撑爆 |
| 静态路由（配置/代码写死） | 开发者 | 可控、零 token，但不灵活 |
| 语义路由（embedding + 分类器） | 预计算的分类器 | 快、便宜，适合粗筛 |

**关键结论：Routing 的效果上限由「工具描述质量」决定，而不是由 router 算法决定。** 工具 description 写得含糊，再花哨的 router 也救不回来。

## 1.2 Tool Routing 与 Multi-Agent Routing 的区别

这是整个设计中最重要的边界划分，两者**正交**，可配合但不可互相替代：

| | 决策粒度 | 本项目实例 |
|---|---|---|
| **Multi-Agent Routing** | 整个任务由哪个 Agent 接手 | `IntentRouter` → AIOps 走 SupervisorAgent，其余走 ReactAgent |
| **Tool Routing** | 单次工具调用选哪个工具 | `buildMethodToolsArray()` 裁剪 + ReAct 内 function calling |

配合方式：AIOps 管道拿恒定 4 个工具、Chat 管道拿 3~11 个工具。

## 1.3 声明 ≠ 强制（贯穿全文的核心原则）

| 维度 | 本质 | 决策时机 | 载体 | 能否被 LLM 绕过 |
|---|---|---|---|---|
| **权限** | 谁能调 | 每次调用 | 声明（给人看）+ **执行期强制校验** | 不可以 |
| **风险等级** | 要不要人确认 / 能否回滚 | 每次调用 | 静态声明 + 部署期白名单 | 不可以 |
| **可用状态** | 现在能不能调 | 启动期 + 调用期 | 带 TTL 的运行期状态源 | 可缓存降级，但须显式 |

三条铁律：

1. **声明不等于强制。** 注解、注册表、配置项都只是"声明"，只服务"给 LLM 看什么"和"配置漂移检测"。**真正的强制发生在工具执行链路上。**
2. **风险等级由代码侧静态声明，由部署侧白名单强制。** 绝不允许 LLM 判断风险，也绝不允许 LLM 输出参与风险升降级。
3. **可用状态必须与配置开关分离。** `@ConditionalOnProperty` 管"启动时装不装"，运行期状态管"请求时能不能用"。

---

# 第二部分 现状盘点

## 2.1 已有基础（2026-09-20 实测）

| 能力 | 现状 | 位置 |
|---|---|---|
| 用户身份 | `SecurityContextHolder` 取 `Authentication.getName()` = userId | `ChatV1Controller:411`、`MemoryV1Controller:122` |
| 认证 | API Key → `ApiKeyAuthenticationToken` | `ApiKeyAuthenticationFilter:55` |
| 授权 | **硬编码只发 `ROLE_USER`**，无角色分层 | `ApiKeyAuthManager:39-42` |
| 身份传入工具 | **`ThreadLocal` 旁路** | `RecallMemoryTool:28-36`，由 `ChatV1Controller:101/135` 在 try/finally 维护 |
| 检索轮次 | **`ThreadLocal`**，靠手动 `reset()` | `AgenticRagGuard:29-32`，`ReactAgentRunner:151` |
| 启动期裁剪 | `@ConditionalOnProperty` + `@Autowired(required=false)` | `SearchKnowledgeBaseTool:30` 等 |
| 外部工具 | MCP 经 `ToolCallbackProvider` 自动注入 | `ReactAgentRunner:409-411` |
| 安全/限流开关 | `security.enabled=false`、`rate-limit.enabled=false` | `application.yml:261,273` |
| API Key 模型 | 只有 `key` / `userId` / `description` | `ApiKeyProperties.ApiKeyEntry:70-74` |
| 评估基建 | `AIOpsEvaluator` / `TestCaseLoader` / `EvalDimension` | `org.example.agent.eval` |

## 2.2 三个已确认的缺陷

### 缺陷 A：`ThreadLocal` 传身份 —— 正确性 + 安全双重风险

链路：Controller 从 SecurityContext 取 userId → 塞进 `ThreadLocal` → 工具再从 `ThreadLocal` 读。

- **`ThreadLocal` 不随执行线程自动传播。** `ReactAgent.call()` 当前跑在请求线程上所以侥幸可用；一旦改为并行工具调用、丢进线程池，或经 `@Async`（项目已有 `AsyncConfig`、`triggerAsyncEvaluation`），身份会**串号或丢失**。
- **串号 = 越权。** 用户 A 的请求读到用户 B 的 `userId` 后调 `recallMemory`，即跨租户数据泄露，且日志上看不出来。
- `AgenticRagGuard` 的 `ThreadLocal` 轮次计数同理，漏调 `reset()` 就是轮次污染。

### 缺陷 B：授权模型无法表达工具权限

`ApiKeyAuthManager:39-42` 对所有 Key 一律发 `ROLE_USER`，`ApiKeyEntry` 无任何权限字段。**当前所有人能调所有工具，权限维度事实上不存在。**

### 缺陷 C：无风险分级、无审计

所有工具一律"只读查询"，无风险标注；`DropCollection` 虽只是 `main()` 工具类未接入 LLM，但已说明**写操作/危险操作的治理是空白**。同时没有工具调用的审计留痕。

## 2.3 当前工具清单（9~11 个）

| 工具 | 方法 | 真实/模拟 | 条件注册 |
|---|---|---|---|
| `DateTimeTools` | `getCurrentDateTime()` | 始终真实 | 无条件 |
| `InternalDocsTools` | `queryInternalDocs(query)` | 真实（Milvus） | 无条件 |
| `QueryMetricsTools` | `queryPrometheusAlerts()` | 真实/模拟 | 无条件 |
| `QueryLogsTools` | `queryLogs(...)`, `getAvailableLogTopics()` | 仅模拟模式 | `cls.mock-enabled=false` 时不注册 |
| `SearchKnowledgeBaseTool` 等 5 个 | agentic RAG 系列 | 真实 | `rag.agentic.enabled=true` |
| `RecallMemoryTool` / `ForgetMemoryTool` | 记忆读写 | 真实 | `memory.enabled=true` |

Chat 管道最多 11 个，AIOps 管道恒定 4 个。

---

# 第三部分 分层 Routing 方案评审

## 3.1 被评审的方案（原文要点）

> ① 维护统一工具注册表，补齐命名空间、标签、适用场景、参数 Schema、权限、风险等级、可用状态。
> ② 每次请求先用产品范围、租户、用户角色、环境和当前任务状态做硬过滤，隐藏无权/不可用工具。
> ③ 两级召回：一级用规则或轻量分类模型判断类别（检索类/订单类/支付类）；二级在类别内用关键词、BM25、Embedding 或混合检索召回少量候选。
> ④ 只把候选工具的完整描述和 Schema 动态绑定给 LLM，让它判断是否调用、选哪个、怎样填参。
> ⑤ Tool Routing 路由「工具候选集」，Multi-Agent Routing 路由「任务由哪个 Agent 接手」，两者可配合但不能混为一谈。

## 3.2 结论：骨架合理，但开错了药

| 维度 | 判断 |
|---|---|
| 三阶段结构（注册表→过滤→召回） | ✅ 合理，行业共识（同 Anthropic Tool Search、RAG-MCP、AnyTool） |
| Tool vs Multi-Agent 的区分 | ✅ 正确且有价值，全篇最好的一句 |
| 硬过滤思路（尤其权限方向） | ✅ 对 |
| 层级切分干净（无语义重叠的相邻层） | ✅ 优于多数方案 |
| 权限只做「隐藏」 | ❌ 安全反模式，必须补执行期校验 |
| 任务状态进硬过滤 | ❌ 应降级为排序特征 |
| 两级召回（当前规模） | ❌ 负收益，且引入新失败模式 |
| 可观测性 / 降级路径 | ❌ 完全缺失 |
| 与实际架构一致性 | ⚠️ 会与 `SkillRegistry` / MCP 形成第三、四套登记处 |

## 3.3 六点具体问题

### ① 规模错配（最重要）

当前 9~11 个工具。全量注入 11 个工具 schema 约 1000~1500 token，占总上下文不到 1%。

- **检索成本 > 节省的 token**：走 Embedding/BM25 需每次请求多一次 embedding 调用 + 检索（或本地建索引 + 分词），省下的微不足道，多花的是真延迟。
- **引入原本不存在的失败模式**：全量注入时正确工具**一定**在候选集内；二级召回一旦漏掉，模型彻底失去发现该工具的机会。把"可能选错"换成了"正确工具不可见"——后者更致命且更难调试。

**分水岭约在 30~50 个工具。当前应归档不实现。**

### ② 与现有机制自相矛盾

- `SkillsAgentHook` + `SkillRegistry`（`SkillsConfig.java`）**已经是**"注册表 + 按需加载"实现，框架自带 `read_skill`，渐进式披露已落地。
- MCP 工具已通过 `ToolCallbackProvider` 自动合并，**不需要手工登记**。

再建一个 registry 会变成**第四套工具登记处**（连同 Spring bean、MCP provider、`SkillRegistry`）——自己想解决的问题，自己会制造一遍。

**将来若真要做（工具数 > 30）的正确形态**：用 `ToolCallbackResolver` / `BeanFactory` 启动期扫描生成注册表，**从容器派生**而非手工维护第二真相源。手工注册表必定与 `buildMethodToolsArray()` 漂移。

### ③ 权限只做「硬过滤」是安全反模式

**隐藏 ≠ 防护**，只降低误调概率，未消除风险。正确做法两级：可见性过滤（体验优化）+ 执行期强制校验（真正的安全边界，须配二次鉴权、超时、限流、审计、高危 dry-run）。

证据：接上 MCP 或前端直连后，**"模型看不见"就等于"谁都能调"**。项目已有 `ApiKeyAuthenticationFilter` / `RateLimitInterceptor`，工具侧应复用同一鉴权上下文。

### ④ 任务状态不该进硬过滤

方案把五个维度塞进同一硬过滤。前四个（产品范围、租户、角色、环境）是**身份/配置快照**，请求内不变、可缓存，适合硬过滤。但**任务状态是会话内演进的**，用它硬过滤会导致：

- 同一句话在会话不同轮次看到的工具集不一致 → **可复现性下降**；
- 状态机抖动 → 工具集抖动 → 模型行为不可预测且难归因。

**任务状态应降为一级的排序特征（soft bias）。**

### ⑤ 缺可观测性，整套路由不可评估

方案未提"路由决策怎么记录"。路由出问题（"知识库明明有答案却不查"）时，**无日志无法定位是召回漏了、过滤掉了、还是一级分错了**。

必须落日志：`requestId / userId / 一级类别 / 各阶段候选集与分数 / 最终实际调用的工具`，并有**"正确工具是否曾出现在候选集内"**指标。

且必须补**降级路径**——项目现有 `IntentRouter` 已做得很好（开关关闭/空输入/LLM 异常/JSON 解析失败/枚举不匹配，五种情况全部 `IntentResult.fallback()` 落 GENERAL_CHAT）。新召回机制须同等具备，否则 embedding 不可用时整条对话链路挂掉，比现在更脆弱。

### ⑥ 二级召回用 Embedding 匹配「工具描述」信号弱

工具选择本质是**意图匹配**，不是**语义相似度匹配**。

更强信号：**为每个工具登记 3~5 条真实历史调用 query 作为范例（exemplar）**，匹配范例比匹配描述准得多。若真到需精排的规模，直接复用项目已有三路 RRF + Rerank 基建（`VectorSearchService`）。

## 3.4 真正该先做的（按 ROI）

原方案诊断错了：它假设病根是"工具太多、上下文太贵"，但真病根是另外三个。

**P0 — 工具描述语义重叠（当前真实的路由失败源）**

```java
InternalDocsTools.queryInternalDocs   : "search internal documentation and knowledge base..."
SearchKnowledgeBaseTool.searchKnowledgeBase : "检索内部知识库。调用前自动用 QueryRewrite 改写查询..."
```

两者语义**几乎无法区分**，`rag.agentic.enabled=true` 时同时注册进同一 Agent → 模型必然随机漂移，且**现有日志看不出它选了哪个、为什么**。

**P1 — 权限与身份传递的工程债**（见第二部分缺陷 A）

**P2 — 复用现有能力裁剪机制**：`@ConditionalOnProperty` + `@Autowired(required=false)` + Skills hook + MCP 已构成可用体系，加新工具先复用它。

**然后才是**：等工具数过 30，或多租户/多环境差异化权限成为真实需求，再按该方案建注册表。

---

# 第四部分 工具治理设计

## 4.1 单一事实来源：`@ToolContract`

> ⚠️ **`@ToolContract` 是本设计提议新建的注解，Spring AI 1.1.2 中不存在**，也无开源实现，需要自行实现。框架自带的只有 `@Tool` / `@ToolParam`。

### 4.1.1 为什么需要它

`@Tool` 与 `@ToolContract` **并列**使用，读者完全不同：

| 注解 | 读者 | 作用 |
|---|---|---|
| `@Tool(description=...)` | **LLM** | 影响"模型想不想调、会不会填对参数" |
| `@ToolContract(...)` | **治理层程序** | 影响"这次调用允不允许放行" |

`description` 写得再好也不能替代 `@ToolContract`，反之亦然。

### 4.1.2 注解定义

```java
package org.example.agent.governance;

import java.lang.annotation.*;

/**
 * 工具治理契约。与 @Tool 并列声明在工具方法上，
 * 由 ModelToolRegistry 在启动期扫描，收敛为只读注册表。
 *
 * 定位：声明"这个工具是什么"（谁来调、多危险、依赖什么），
 *       不负责"这次允不允许"（那由部署期白名单与运行期校验决定）。
 */
@Retention(RetentionPolicy.RUNTIME)      // 必须运行期可见，启动扫描靠反射
@Target(ElementType.METHOD)              // 只标方法，与 @Tool 一致
@Documented
public @interface ToolContract {

    /** 命名空间：工具的稳定归属分类 */
    String namespace();

    /** 风险等级 */
    RiskLevel risk() default RiskLevel.READ_ONLY;

    /** 调用所需权限 scope（AND 语义）；空数组 = 仅需登录 */
    String[] scopes() default {};

    /** 依赖的可用性探针 key；空数组 = 无外部依赖 */
    String[] probes() default {};

    /** 是否进入"给 LLM 的候选集"；false = 仅供内部编排/子 Agent 调用 */
    boolean modelVisible() default true;

    /** 单次调用超时毫秒；-1 = 用全局默认 */
    long timeoutMs() default -1;         // ← 见 4.1.5 补充项
}

public enum RiskLevel {
    READ_ONLY,        // 纯查询，无副作用，自动放行
    LOW_RISK_WRITE,   // 用户自己数据的写操作（如 forgetMemory）
    MUTATING,         // 影响共享状态（如重建索引）
    DANGEROUS         // 不可逆/破坏性（如 drop collection）
}
```

`@Retention(RUNTIME)` 是必须的——写成 `CLASS` 则启动扫描读不到。

### 4.1.3 逐个参数用途

#### `namespace`（必填，无默认值）

**用途**：工具的稳定归属分类。三个消费点：

- **审计聚合**：可问"最近 `logs` 命名空间的工具拒绝率多少"
- **批量策略**：配置里整组放开/关闭（`namespace: memory`）
- **一级类别路由**：工具超 30 需两级召回时，它就是一级分类载体

**为什么不从包名推导**：包名随重构变化，命名空间是治理语义需稳定；且 MCP 工具没有你的包路径，只有显式声明才能让两类工具在同一分类体系下对齐。

> **权衡提示**：若不打算做两级召回（按当前规模确实不该做），`namespace` 就只剩审计聚合一个用途，可考虑砍掉或从方法名前缀推导。倾向保留，但须知这是"为将来付费"。

#### `risk`（默认 `READ_ONLY`）

**用途**：决定执行期要不要额外关卡。消费点全在 `GovernedToolCallback`：

| 等级 | 执行期行为 |
|---|---|
| `READ_ONLY` | 登录 + scope → 放行 |
| `LOW_RISK_WRITE` | 上级 + 审计留痕 |
| `MUTATING` | 需显式 `admin:write` + 强制审计 + 禁止重试 |
| `DANGEROUS` | 上级 + 人工确认 token，且**默认不注册** |

**默认值取舍**：默认给了"无声明即放行"语义，安全上不理想，但执行期校验兜住（仍需过登录 + scope）。真正防线是 4.4 节的启动自检（方法名命中 `drop|delete|remove` 等词表却标低风险 → 启动失败）。

**关键约束**：`risk` **只读不写**。运行期任何代码路径（尤其模型输出）都不能修改。模型可在回复里说"这步很安全"，但那只是字符串，不影响 `contract.risk()`。

#### `scopes`（默认空数组）

**用途**：工具级细粒度授权，能力标签。

**空数组语义**：仅需登录（如 `getCurrentDateTime`）。

**与角色的关系（最易混淆处）**：

```
role  = 身份属性，来自认证：ApiKeyAuthManager 发的 ROLE_SRE / ROLE_USER
scope = 能力标签，来自工具声明：docs:read / logs:read / admin:write

放行条件：当前身份的 scope 集合 ⊇ 工具声明的 scopes
```

**角色是"你是谁"，scope 是"你能碰什么"**，两者正交。需要一层映射：`ROLE_SRE → {docs:read, metrics:read, logs:read, admin:write}`，`ROLE_USER → {docs:read}`。多角色取**并集**。

**数组语义定死为 AND**：OR 语义会让权限模型难以审计。

**现实提醒**：项目现在 `ApiKeyAuthManager:39-42` 对所有 Key 硬编码只发 `ROLE_USER`，**scope 体系尚不存在**。落地前须先扩展 `ApiKeyEntry` 加 `roles` 字段——这是一切的前置依赖。

#### `probes`（默认空数组）

**用途**：声明工具依赖哪个外部服务，供 `ToolAvailability` 判定"现在能不能用"。

**空数组语义**：无外部依赖，永远可用。

**消费链路**：

```
probes = {"cls-mcp"}
   → ToolAvailability.isAvailable("cls-mcp")
   → 查 TTL 缓存（默认 30s），未命中则调 AvailabilityProbe
   → 明确 DOWN   → 隐藏该工具，且直接调用被拒（fail-closed）
   → 探测超时/未知 → 仍注入（fail-open），由工具内部异常兜底
```

**为什么是探针 key 而不是 URL**：注解是**编译期常量**，不能读 `application.yml`。故注解只放逻辑名 `"cls-mcp"`，真实地址由 `AvailabilityProbe` 实现自行读取配置。**这是注解与配置之间必须的间接层**，否则 URL 就得硬编码进源码。

**数组语义为 AND**：多依赖时任一 DOWN 即不可用。

#### `modelVisible`（默认 `true`）

**用途**：控制是否进入"给 LLM 的候选集"。

**`false` 语义**：工具注册了、内部代码能调，但**不告诉模型它存在**。主要用于**子 Agent 专用工具**和**编排内部工具**——比靠 prompt 说"别调这个"可靠得多。将来两级召回时也应排除在召回语料之外。

**重要**：`modelVisible=false` 的工具**权限校验仍要走**。容易误以为"模型看不见就不用管权限"——不对，内部编排代码调用时一样要过身份校验，否则就是绕过通道。

#### `timeoutMs`（补充项，本设计新增）

`queryLogs` 查 CLS 可能比 `getCurrentDateTime` 慢几个数量级，原设计缺超时粒度。加此参数比风格化的 `namespace` 更实用。

### 4.1.4 放到真实工具上看

```java
// InternalDocsTools.java
@Tool(description = "Use this tool to search internal documentation and knowledge base...")
@ToolContract(namespace = "docs", risk = RiskLevel.READ_ONLY, scopes = {"docs:read"},
              probes = {"milvus"})
public String queryInternalDocs(@ToolParam(...) String query) { ... }


// QueryLogsTools.java —— 两个方法各自独立声明
@Tool(description = "Get all available log topics and their descriptions...")
@ToolContract(namespace = "logs", risk = RiskLevel.READ_ONLY, scopes = {"logs:read"},
              probes = {"cls-mcp"})
public String getAvailableLogTopics() { ... }

@Tool(description = "Query logs from Cloud Log Service (CLS)...")   // 原第150行
@ToolContract(namespace = "logs", risk = RiskLevel.READ_ONLY, scopes = {"logs:read"},
              probes = {"cls-mcp"})
public String queryLogs(...) { ... }


// ForgetMemoryTool.java —— 用户自己数据的写操作
@Tool(description = "删除用户记忆...")
@ToolContract(namespace = "memory", risk = RiskLevel.LOW_RISK_WRITE,
              scopes = {"memory:write"}, probes = {"milvus"})
public String forgetMemory(...) { ... }


// DateTimeTools.java —— 最简形态
@Tool(description = "Get the current date and time in the user's timezone")
@ToolContract(namespace = "system")     // risk 默认 READ_ONLY，scopes/probes 默认空
public String getCurrentDateTime() { ... }
```

启动扫描后的只读元数据视图：

```
docs:queryInternalDocs     → {scope:[docs:read],    probe:[milvus],  risk:READ_ONLY,      visible:true}
logs:queryLogs             → {scope:[logs:read],    probe:[cls-mcp], risk:READ_ONLY,      visible:true}
logs:getAvailableLogTopics → {scope:[logs:read],    probe:[cls-mcp], risk:READ_ONLY,      visible:true}
memory:forgetMemory        → {scope:[memory:write], probe:[milvus],  risk:LOW_RISK_WRITE, visible:true}
system:getCurrentDateTime  → {scope:[],             probe:[],        risk:READ_ONLY,      visible:true}
```

### 4.1.5 已知设计缺口（诚实记录）

1. **参数级护栏缺声明载体。** 装饰器无法知道某工具的哪个参数该限多少，只能靠全局默认（如所有 `topK` ≤ 20），无法表达"这个工具允许 topK=100，那个只允许 10"。真要做到位需 `@ParamGuard(max = 20)` 之类参数级注解。**这是真实缺口，非细节。**
2. **`timeoutMs` 为本次新增**，尚未验证与全局 `aiops.total-timeout-seconds` 的优先级关系。
3. **`namespace` 的 ROI 依赖是否做两级召回**，见 4.1.3 权衡提示。

## 4.2 注册表：派生视图，不是第二真相源

`ModelToolRegistry` 启动期扫描所有 `@Tool` + `@ToolContract` 方法（含 MCP 来源），产出**只读**元数据视图。

**关键约束**：注册表是**派生视图**，所有元数据来自注解，注册表只做索引与查询。这样才不会制造出第四套工具登记处。

MCP 工具因代码不在手边，加不了注解，走外部策略表 `tool-governance.yml`：

```yaml
# src/main/resources/tool-governance.yml
mcp:
  tencent-cls:
    tools:
      - name-pattern: "cls_*_search"
        namespace: logs
        risk: READ_ONLY
        scopes: [logs:read]
        probes: [cls-mcp]
      - name-pattern: "cls_*_delete"
        namespace: logs
        risk: MUTATING
        scopes: [admin:write]
    unmatched-policy: deny      # 匹配不到策略的 MCP 工具默认不注册（default-deny）
```

**三类配置的分工**（不要混淆）：

| 位置 | 配什么 | 谁改 | 何时生效 |
|---|---|---|---|
| 工具方法上的 `@ToolContract` | 风险等级、scope、命名空间、探针、超时 | 开发者 | 需重新构建 |
| `tool-governance.yml` | **MCP 工具的等价声明** | 开发者/运维 | 重启 |
| `application.yml` | **只配执行开关** | 运维 | 重启 |

**为什么风险等级必须放在代码里**：它是**安全断言**，不是可调参数。落到 `application.yml` 就等于"改一个环境变量就能把 `drop_collection` 标成 `READ_ONLY`"——那不是治理，是留后门。安全性元数据只有待在代码里、跟着 code review 走，才有约束力。附带好处：`@Tool` 与 `@ToolContract` 相邻，**review 时改实现的人必定看到风险标注**。

`application.yml` 只放开关：

```yaml
superbiz:
  security:
    enabled: false                      # 全局安全开关
    api-key-header: X-API-Key
    api-keys:
      - key: ${SRE_API_KEY}
        user-id: sre-001
        roles: [SRE]                    # 新增
        tool-scopes: [docs:read, metrics:read, logs:read, admin:write]   # 新增
      - key: ${BIZ_API_KEY}
        user-id: biz-002
        roles: [USER]
        tool-scopes: [docs:read]        # 只能查文档，看不到日志/监控工具
  governance:
    dangerous-tools-enabled: []         # 默认空 = 所有 DANGEROUS 工具都不注册
    # dangerous-tools-enabled: [drop_collection]    # 需要时逐项放开
```

**双份设计的分工**：

```
@ToolContract(risk = DANGEROUS)                 ← 代码侧：声明"它很危险"
        +
dangerous-tools-enabled: [drop_collection]      ← 配置侧：声明"这次允许它生效"
```

**代码说"它是什么"，配置说"这次允不允许"**，两者都满足才注册。新增危险工具时：合入代码 → 启动 → 白名单为空 → **不会自动生效**，必须再显式加一行配置。这道人工闸门就是防"危险工具静默上线"。

## 4.3 权限：可见性过滤 + 执行期强制

**第一级 — 可见性过滤**（体验优化，省 token）：按工具的 `scopes` 与当前 `Authentication` 的 authority 取交集，无交集则剔除。**只在"给 LLM 看什么"处生效。**

**第二级 — 执行期强制**（真正的安全边界）：用 `ToolCallback` 装饰器包裹每个已注册工具，**在参数进入真实方法之前**校验。

### 4.3.1 与 Spring AI 实际接口的对接（已核对 1.1.2）

核对 `spring-ai-model-1.1.2.jar` 得到 `ToolCallback` 真实签名：

```java
public interface ToolCallback {
    ToolDefinition getToolDefinition();                       // 抽象
    ToolMetadata   getToolMetadata();                         // 有默认实现
    default String call(String toolInput) {                   // 有默认实现，委托给下面那个
        return call(toolInput, ToolContext.EMPTY);
    }
    String call(String toolInput, ToolContext toolContext);    // 抽象 ← 装饰器只需覆写这一个
}
```

**这一结构对装饰器极为有利**：`call(String)` 是 default 且委托给两参版本，**只覆写 `call(input, toolContext)` 就能拦下所有调用路径**，不必担心有哪条路绕过。

```java
public class GovernedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolContract contract;    // 启动期从注解解析出的元数据
    private final AuditLogger audit;

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); } // 透传
    @Override public ToolMetadata   getToolMetadata()   { return delegate.getToolMetadata(); }   // 透传

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        var auth = SecurityContextHolder.getContext().getAuthentication();   // 实时取，不缓存
        // ① 身份  ② scope  ③ 可用性(probes)  ④ 风险(人工确认)  ⑤ 参数护栏  ⑥ 审计
        // 任一不过 → 抛异常 + 审计 deny
        return delegate.call(toolInput, toolContext);   // 全过才放行
    }
}
```

`getToolDefinition()` 必须**原样透传**——否则工具名和参数 schema 变了，模型就认不出来了。

**附带发现（值得决策）**：`ToolContext` 是 Spring AI 提供的**每次调用的显式参数传递机制**，比 `ThreadLocal` 干净得多。项目现用静态 `ThreadLocal` 传 userId，其实框架已给正规通道。它与"改用 `SecurityContextHolder`"两条路都可行，前者更符合框架设计意图，但**要求上游在发起 Agent 调用时构造 `ToolContext`**，改动面更大。建议放到阶段一一起决策。

### 4.3.2 身份传递：废弃 `ThreadLocal` 旁路

- 工具内一律 `SecurityContextHolder.getContext().getAuthentication()` 取 userId，删除 `RecallMemoryTool.setCurrentUserId/clearCurrentUserId`。
- 装饰器是统一入口，天然能拿到身份，无需每个工具自维护。
- 若后续 Agent 并行化导致线程切换，用 `DelegatingSecurityContextExecutor` 显式传播（或 `MODE_INHERITABLETHREADLOCAL`），**而不是再引入一个 `ThreadLocal`**。
- `AgenticRagGuard` 的轮次计数应改为按 `sessionId` 索引的显式状态，而非线程绑定。

## 4.4 启动自检（fail-fast）

`@ToolContract` 是代码侧声明，固有弱点是**工具实现升级了、标注忘改**（如 `queryInternalDocs` 后来加了删除能力却仍是 `READ_ONLY`）。用启动自检兜住：

```java
// ModelToolRegistry 启动期扫描，任一条不满足即 fail-fast，拒绝启动
1. 每个 @Tool 必须有 @ToolContract            → 否则启动失败（防绕过治理）
2. 方法名/参数名命中危险词表
   (drop|delete|remove|truncate|reset|purge|kill|shutdown)
   但 risk 只有 READ_ONLY/LOW_RISK_WRITE      → 启动失败（防漏标）
3. 声明了 probes 的探针 key 必须真实存在      → 否则启动失败（防笔误）
4. @ToolContract 声明的 scope 与
   tool-governance.yml 策略表不一致            → 启动失败（防漂移）
```

漏改会在**启动时**炸掉，而不是线上被人调用了才发现。

## 4.5 风险等级的四级行为

| 等级 | 模型可见 | 执行期要求 | 处理方式 |
|---|---|---|---|
| `READ_ONLY` | 是 | 登录 + 对应 scope | 直接放行 |
| `LOW_RISK_WRITE` | 是 | 登录 + scope + **审计留痕** | 放行，异步审计 |
| `MUTATING` | 是 | scope + **显式 `admin:write`** + 审计 | 放行，强制审计 |
| `DANGEROUS` | 是 | 以上全部 + **人工确认 token** | **默认不注册** |

**三条强制规则**：

1. **`DANGEROUS` 默认不注册（default-deny）**，经 `governance.dangerous-tools-enabled` 白名单逐项放开。
2. **风险等级不可被输入影响**。LLM 输出的任何字段（含 `riskLevel`、`confirmed`）都不参与风险判定——**模型可以提议，不可以授权。**
3. **参数护栏与风险等级绑定**，在装饰器统一执行。现状 `Math.min(topK, 20)` 这类判断散在 `SearchKnowledgeBaseTool:55`、`RecallMemoryTool:50`、`QueryLogsTools` 里，应上收，避免漏一个就破防。

## 4.6 审计

**最小字段集**：`timestamp / requestId / sessionId / userId / toolName / namespace / risk / decision(allow|deny) / denyReason / durationMs / 入参摘要(脱敏) / 结果状态`。

**必须脱敏**：审计不得记录完整入参明文（日志查询可能含敏感信息），只记摘要 + hash。

## 4.7 可用状态：运行期状态源 + TTL + 降级

**与配置开关的关系**：

- `@ConditionalOnProperty`（启动期）：决定 bean 装不装，**改了要重启**，适合"能力归属"（如 `rag.agentic.enabled`）。
- `ToolAvailability`（调用期）：决定这次调用能不能用，**实时生效**，适合"依赖健康 / 配额 / 降级 / 租户级灰度"。

```java
public interface AvailabilityProbe {
    String key();                 // e.g. "milvus", "prometheus", "cls-mcp"
    ProbeResult probe();          // OK / DEGRADED / DOWN + reason
    Duration ttl();               // 建议默认 30s
}

@Component
public class ToolAvailability {
    // 带 TTL 的缓存 + 单飞(single-flight)刷新，避免探活风暴
    // 探针状态未知(未探测/超时) → 按"可用"放行，由工具自身异常兜底
}
```

**语义决策（须显式统一）**：

| 场景 | 决策 | 理由 |
|---|---|---|
| 依赖健康探测失败 | **fail-closed**：隐藏该工具并标记不可用 | 避免模型反复调必然失败的工具，浪费轮次与 token |
| 探针自身超时/未知 | **fail-open**：仍注入该工具 | 探测故障不应导致能力整体消失；由工具内部异常兜底 |
| 租户级灰度 | 按 `userId`/tenant 命中灰度名单才可用 | 支持新工具小流量验证 |

**降级路径（必须实现）**：任何探针/缓存异常，一律**回退为"全部已注册工具可用"**，即治理层故障不得阻塞对话。与 `IntentRouter` 现有五种 fallback 写法保持一致。

**工具内部优雅降级范式**：`SearchKnowledgeBaseTool:84-92` 异常时返回带 `_meta` 的错误 JSON 而非抛异常，应作为**查询类工具的规范**——但**写操作必须抛异常**，不能静默返回错误 JSON 让模型误以为成功。

## 4.8 三层协作全景

```
请求进入
  │
  ├─[启动期] @ConditionalOnProperty 决定 bean 是否存在            ← 能力归属
  ├─[启动期] @ToolContract 扫描 → ModelToolRegistry + 启动自检     ← 单一事实来源
  ├─[启动期] governance.dangerous-tools-enabled 白名单             ← 危险工具默认不注册
  │
  ├─[调用期·可见性] scope ∩ authority  →  决定注入给 LLM 的候选集   ← 体验优化
  ├─[调用期·可见性] ToolAvailability.isAvailable() → 同上          ← 运行期状态
  │
  └─[调用期·强制] GovernedToolCallback
        身份 → scope → 可用性 → 风险(人工确认) → 参数护栏 → 审计 → 放行
                                                                  ← 安全边界（唯一强制点）
```

**最重要的架构性质：可见性过滤可失效、可降级、可有 bug，安全性都不受影响**，因为强制点只有装饰器一处。这是相对"靠隐藏来保护"的根本改进。

---

# 第五部分 实施计划

## 阶段一（P0，独立可交付）：修 `ThreadLocal` 身份传递

- 删除 `RecallMemoryTool.setCurrentUserId/clearCurrentUserId`，改为工具内读 `SecurityContextHolder`。
- `ChatV1Controller:101,135` 与 `chatStream` 分支同步清理。
- `AgenticRagGuard` 的 `ThreadLocal` 改为按 `sessionId` 显式状态。
- 决策：是否改用 Spring AI 的 `ToolContext` 通道（见 4.3.1）。
- 补测试：并发两用户同时调 `recallMemory`，断言不串号。

> 此阶段不依赖任何新架构，**是纯缺陷修复，建议优先独立开 feature 分支合入**。

## 阶段二（P1）：契约 + 权限 + 审计

- 加 `@ToolContract` / `RiskLevel` / `ModelToolRegistry`（含启动 fail-fast 自检）。
- 扩展 `ApiKeyProperties.ApiKeyEntry`：`roles` / `toolScopes`；`ApiKeyAuthManager` 按配置发 authority。
- 实现 `GovernedToolCallback` 装饰器，接入 `ReactAgentRunner` 工具注册处。
- 实现审计日志（结构化 + 入参脱敏）。
- 参数护栏上收（`topK` / `limit` 上限统一在装饰器裁剪）。
- `application.yml` 的 `superbiz.security` 补 `roles` / `tool-scopes` 示例与注释。

## 阶段三（P2）：可用性探针 + 风险治理

- `AvailabilityProbe` + `ToolAvailability`（TTL 缓存、单飞刷新、fail-open 兜底）。
- 接 Milvus（复用 `MilvusCheckController` 健康检查）、Prometheus、CLS-MCP 三个探针。
- `DANGEROUS` 人工确认通道（默认不注册 + 白名单开放）。
- MCP 工具外部策略表 `tool-governance.yml`（未匹配默认不注册）。

## 阶段三补充（P1.5）：修工具描述语义重叠

`queryInternalDocs` 与 `searchKnowledgeBase` 的 description 需写出**明确选择边界**（何时用 A、何时用 B、互斥关系）。这是当前规模下 ROI 最高的单项改动。

## 阶段四（P3，条件触发）：动态候选集

> ⚠️ **前置条件：工具总数 > 30。** 当前仅 9~11 个，两级召回为负收益，详见第三部分。此阶段仅登记，不实施。

---

# 第六部分 验收标准与风险

## 6.1 验收标准

| 项 | 验收方式 |
|---|---|
| 身份不串号 | 并发双用户测试，`recallMemory` 各自只返回自己的记忆 |
| 权限强制有效 | 构造无 scope 用户，**直接从工具层调用**（绕过可见性过滤）仍被拒绝 |
| 默认拒绝 | 新增不带 `@ToolContract` 的 `@Tool` → 应用启动失败 |
| 危险工具默认关闭 | 不配白名单时 `DANGEROUS` 工具不出现在候选集，且直接调用被拒 |
| 模型无法自我授权 | 入参注入 `{"riskLevel":"READ_ONLY","confirmed":true}` 不影响风险判定 |
| 可用性降级 | 关闭 Milvus → 依赖工具被隐藏且标记不可用；探针抛异常 → 回退全可用，对话不中断 |
| 审计完整 | 每次工具调用产生一条结构化审计，含 decision / denyReason / 耗时 |
| 审计脱敏 | 审计中不含完整查询明文（仅摘要 + hash） |
| 配置漂移 | 改 `@ToolContract` 元数据后，启动自检能发现与策略表不一致 |
| 描述可区分 | `queryInternalDocs` vs `searchKnowledgeBase` 的 description 有明确互斥边界 |
| `modelVisible=false` 仍受权限约束 | 内部代码调用该工具，未认证时被拒 |

## 6.2 风险与权衡

| 风险 | 缓解 |
|---|---|
| 装饰器加一层间接，可能影响流式事件（`AGENT_TOOL_FINISHED`） | 装饰器透传 `ToolDefinition` 与名称，不改事件结构；补流式回归测试 |
| MCP 工具无法加注解，策略易漏 | 未匹配策略的 MCP 工具**默认不注册**（default-deny），启动日志打印已加载工具清单 |
| `security.enabled=false` 期间治理层是否生效 | 治理层**独立于** `security.enabled`：未认证时按"匿名身份仅可用无 scope 工具"处理，避免开关关闭即全面失守 |
| 探针增加外部依赖抖动 | TTL ≥ 30s + 单飞刷新 + 异常 fail-open；探针不得阻塞调用线程 |
| 注册表与注解漂移 | 注解为唯一真相源，注册表纯派生；启动自检 fail-fast |
| `@ToolContract` 是自研注解，长期维护成本 | 元数据量小（5~6 字段），且是纯声明无逻辑；若框架将来提供等价能力应迁移 |
| 参数级护栏缺口（见 4.1.5） | 一期先用装饰器全局默认，二期补 `@ParamGuard` |

---

# 第七部分 待确认问题

1. **角色体系**：引入独立角色（`SRE` / `USER` / `ADMIN`），还是仅用 `tool-scopes` 表达权限？（前者贴合 `GrantedAuthority` 模型，后者更细粒度）
2. **租户维度**：`ApiKeyEntry` 现只有 `userId`、无 `tenantId`。要做租户级工具隔离需先补字段并贯穿 `MemorySearchService` 等存储层——现在做还是先不做？
3. **`DANGEROUS` 确认形态**：API 二次确认（返回确认 token）还是运维审批流？
4. **审计落盘**：复用现有日志体系，还是独立审计表 / 接 Langfuse（项目已接入，见 `2026-08-31-langfuse-observability-design.md`）？
5. **`ToolContext` vs `SecurityContextHolder`**：身份传递选哪条路（见 4.3.1）？
6. **`namespace` 是否保留**：若确定不做两级召回，其价值仅剩审计聚合（见 4.1.3）。

---

# 第八部分 一句话总结

**权限、风险、可用状态三者都要"声明 + 强制"双份：声明让模型看得对，强制让越权调不动。**

当前项目缺的不是声明，是强制——且最紧迫的是先把 `ThreadLocal` 身份旁路改成 Spring Security 上下文，那是**已经存在的越权风险，不需要等治理框架落地**。

至于分层 Routing：**骨架正确，但现在不该实现**——9~11 个工具的规模下两级召回是负收益，真正该做的是修身份传递（P0）、补工具描述边界（P1.5）、建审计与评估闭环（P1）。
