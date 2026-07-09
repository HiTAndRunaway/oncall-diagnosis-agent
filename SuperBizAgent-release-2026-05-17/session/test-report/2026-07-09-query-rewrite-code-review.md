# 查询改写层代码审查报告

> 审查日期: 2026-07-09 | 分支: feature/hybrid-recall-rrf | 审查人: code-review-expert

## Code Review Summary

**Files reviewed**: 7 new + 2 modified = 9 files, ~360 lines added
**Overall assessment**: APPROVE (with P1 items recommended to fix)

---

## Findings

### P1 - High

**1. [QueryRewriteProperties.java:24] 声明但未使用的 `timeout` 配置项**

`Duration timeout` 字段在配置属性类中声明并可通过 yml 配置，但在 `QueryRewriteService` 中从未被消费。`DashScopeChatModel` 使用自己的默认超时，而 `DashScopeChatOptions` 也未被设置 timeout。

- **影响**: 用户在 yml 中配置 `rag.rewrite.timeout: 30s` 实际上不生效，LLM 调用使用的是 SDK 默认超时，可能导致阻塞时间不符合预期
- **修复**: 在 `createRewriteChatModel()` 中通过 `DashScopeChatOptions` 设置超时，或删除该配置项

**2. [QueryRewriteService.java:87] `instanceof DirectStrategy` 破坏多态**

```java
if (strategy instanceof DirectStrategy) {
    return strategy.rewrite(originalQuery);
}
```

协调服务通过类型判断来决定是否跳过缓存/重试逻辑，违反了 LSP。如果将来需要新增一个不需要 LLM 的策略，此处代码必须修改。

- **影响**: 扩展性降低，每次新增非 LLM 策略都需要修改此处
- **修复**: 在接口中增加 `default boolean requiresLlm() { return true; }`，让 DirectStrategy 覆写返回 `false`

### P2 - Medium

**3. [多个文件] `truncate()` 工具方法重复 4 次**

`truncate()` 方法在 `QueryRewriteService`、`PromptRewriteStrategy`、`HypotheticalAnswerStrategy`、`DetailAbstractStrategy` 中完全相同的实现，违反 DRY 原则。

- **修复**: 提取到 `QueryRewriteStrategy` 接口的 `default` 方法，或创建独立工具类

**4. [PromptRewriteStrategy.java:10] 未使用的 import**

```java
import java.util.List;  // 未使用
```

- **修复**: 删除该 import

**5. [QueryRewriteService.java:44] 未使用的字段 `rewriteChatModel`**

该字段仅在 `init()` 中通过 `createRewriteChatModel()` 被赋值，之后不再被读取。策略实例在 `init()` 中创建后直接持有 ChatModel 引用，该字段成为冗余。

- **修复**: 移除字段，将 `createRewriteChatModel()` 改为直接返回结果

**6. [QueryRewriteService.java:137] `CompletableFuture.runAsync()` 使用公共线程池**

异步缓存写入使用 `ForkJoinPool.commonPool()`。虽然在此场景风险较低（fire-and-forget 缓存写入），但在高并发时可能与 JVM 其他任务争抢线程。

- **修复**: 使用自定义 Executor 或 Spring 的 `TaskExecutor`

### P3 - Low

**7. [DirectStrategy.java:17] DEBUG 日志每次调用都输出**

`DirectStrategy.rewrite()` 每次调用都输出 DEBUG 日志，在默认策略（direct）和高频调用场景下会产生大量无意义日志。

- **修复**: 移除该日志或改为仅在首次调用时输出

**8. [QueryRewriteService.java:40-41] 混合构造器注入与字段注入**

`properties` 和 `redisTemplate` 使用构造器注入，但 `dashscopeApiKey` 使用 `@Value` 字段注入，风格不统一。

- **修复**: 统一使用构造器注入：在构造器中添加 `@Value` 参数

---

## SOLID 评估

| 原则 | 评分 | 说明 |
|------|------|------|
| SRP | ✅ 良好 | 每个类职责单一，QueryRewriteService 作为协调器有多个职责但属合理 |
| OCP | ⚠️ 可改进 | 新增策略需修改枚举 + switch，可用注册模式改进（见 P1#2） |
| LSP | ✅ 良好 | 所有子类正确实现接口契约 |
| ISP | ✅ 良好 | 单方法接口，精简 |
| DIP | ⚠️ 可改进 | 协调服务直接实例化策略，可考虑 Spring Bean 注入 |

## 安全评估

| 检查项 | 状态 |
|--------|------|
| 注入风险 | ✅ 无 SQL/NoSQL/命令注入 |
| 密钥泄露 | ✅ truncate 截断日志，不输出 API Key |
| 路径遍历 | ✅ 无文件操作 |
| SSRF | ✅ LLM 调用目标固定为 DashScope API |
| MD5 使用 | ✅ 仅用于缓存 key（非安全用途） |
| 线程安全 | ✅ 策略实例在 @PostConstruct 创建，发布后不可变 |

## 性能评估

| 检查项 | 状态 |
|--------|------|
| 缓存策略 | ✅ Redis 缓存 + TTL 可配置 |
| N+1 查询 | ✅ 不涉及 |
| 异步写入 | ✅ 缓存写入异步（P2#6 建议优化线程池） |
| 重试退避 | ✅ 指数退避 5s→25s→125s |
| 边界条件 | ✅ null/空字符串正确处理 |

## 审查结论

代码整体质量良好，策略模式设计合理，异常处理和降级逻辑完整。发现 **2 个 P1 问题**（timeout 配置未生效、instanceof 破坏多态）和 **4 个 P2 问题**（代码重复、未使用 import、冗余字段、线程池优化），建议修复 P1 和 P2 后提交。
