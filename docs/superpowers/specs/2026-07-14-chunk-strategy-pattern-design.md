# 文档切分策略模式设计

> 日期：2026-07-14 | 分支：feature/hybrid-recall-rrf | 状态：待实现

## 1. 目标

将文档切分从单一算法重构为**可配置的策略模式**，支持以下策略：

| 策略 | strategyName | 说明 |
|------|-------------|------|
| 标题拆分（现有） | `heading` | 保留现有逻辑，即按 Markdown 标题 → 段落 → 固定大小 + 重叠 |
| 固定大小 + 重叠 | `fixed-size` | 纯固定大小切割，不感知文档结构 |
| 语义边界切割 | `semantic` | 按段落 + 句子边界切分，保持语义完整 |
| Parent-Child | `parent-child` | 小块检索 + 大块返回（small-to-big） |

约束：
1. 现有 `DocumentChunkService` 代码**不改一行**，通过适配器纳入策略体系
2. 全局默认策略 + 可按文件扩展名覆盖
3. 策略切换通过 `application.yml` 配置

## 2. 架构

### 2.1 整体架构

```
                      application.yml
                           │
                    ChunkStrategyProperties (配置绑定)
                           │
    ┌──────────────────────┼──────────────────────┐
    │                      │                      │
    ▼                      ▼                      ▼
HeadingChunkStrategy  FixedSizeStrategy  SemanticBoundaryStrategy  ParentChildStrategy
    │                      │                      │                      │
    └──────────────────────┴──────────────────────┴──────────────────────┘
                           │
                    ChunkStrategyFactory (根据 config + 扩展名 路由)
                           │
                    VectorIndexService
                           │
                    VectorSearchService (仅 parent-child 时做 resolve)
```

分层职责：
- **策略实现层**：各自的切分算法，互不感知
- **工厂层**：根据配置选择策略
- **消费层**：`VectorIndexService` 调工厂，`VectorSearchService` 做检索侧后处理

### 2.2 组件清单

| 组件 | 层 | 路径 | 说明 |
|------|---|------|------|
| `DocumentChunkStrategy` | 接口 | `service/chunk/DocumentChunkStrategy.java` | 策略接口 |
| `HeadingChunkStrategy` | 策略 | `service/chunk/HeadingChunkStrategy.java` | 委托给现有 `DocumentChunkService` |
| `FixedSizeChunkStrategy` | 策略 | `service/chunk/FixedSizeChunkStrategy.java` | 固定大小 + 重叠 |
| `SemanticBoundaryStrategy` | 策略 | `service/chunk/SemanticBoundaryStrategy.java` | 段落/句子边界 |
| `ParentChildStrategy` | 策略 | `service/chunk/ParentChildStrategy.java` | small-to-big 检索 |
| `ChunkStrategyFactory` | 工厂 | `service/chunk/ChunkStrategyFactory.java` | 路由选择 |
| `ChunkStrategyProperties` | 配置 | `config/ChunkStrategyProperties.java` | 配置绑定 |
| `DocumentChunkService` | 现有 | `service/DocumentChunkService.java` | **不改** |
| `VectorIndexService` | 消费 | `service/VectorIndexService.java` | 注入工厂替代直接注入 |
| `VectorSearchService` | 消费 | `service/VectorSearchService.java` | 新增 `resolveParentContent()` |

### 2.3 与现有 DocumentParser 策略模式的对比

| 维度 | DocumentParser | DocumentChunkStrategy |
|------|---------------|----------------------|
| 接口风格 | `supportedExtensions()` + `parse()` | `strategyName()` + `chunk()` |
| Bean 注册 | `@Component`，Spring 自动收集 | 同 |
| 注入方式 | `List<DocumentParser>` 构造函数注入 | `List<DocumentChunkStrategy>` 构造函数注入 |
| 路由方式 | 按扩展名直接查 Map | 按扩展名查配置 → 按 strategyName 查策略 |

## 3. 接口设计

```java
// service/chunk/DocumentChunkStrategy.java
package org.example.service.chunk;

import org.example.dto.DocumentChunk;
import java.util.List;

public interface DocumentChunkStrategy {

    /** 策略标识，与配置项 strategy-name 对应 */
    String strategyName();

    /** 执行文档分片 */
    List<DocumentChunk> chunk(String content, String filePath);
}
```

注意：接口上不放 `supportedExtensions()`。扩展名路由是配置层的职责，策略本身只管"怎么切"。

## 4. 各策略详设

### 4.1 HeadingChunkStrategy（标题拆分，适配现有逻辑）

```java
@Component
public class HeadingChunkStrategy implements DocumentChunkStrategy {

    @Autowired
    private DocumentChunkService delegate;

    @Override
    public String strategyName() { return "heading"; }

    @Override
    public List<DocumentChunk> chunk(String content, String filePath) {
        return delegate.chunkDocument(content, filePath);
    }
}
```

- `DocumentChunkService` 仍是独立的 `@Service` Bean，保持向后兼容
- `HeadingChunkStrategy` 是纯适配器，零业务逻辑

### 4.2 FixedSizeChunkStrategy（固定大小 + 重叠）

```
算法：
1. 从 content 开头取 maxSize 个字符作为第一个 chunk
2. 指针后移 (maxSize - overlap) 个字符，取下一个 chunk
3. 重复直到文本末尾
4. 不对齐标题、段落、句子边界（最简单、最原始的策略）
```

配置项：`max-size`（默认 500）、`overlap`（默认 100）

### 4.3 SemanticBoundaryStrategy（语义边界切割）

```
算法：
1. 按 \n\n+（空行/段落边界）粗切为段落列表
2. 逐段落拼接，当前 chunk + 下一段落 ≤ max-size 时继续拼接
3. 超过 max-size 时：如果单个段落已超 max-size，按句子边界（。？！\n）切分该段落
4. 每个新 chunk 以 overlap 字符（对齐句子边界）开头
```

配置项：`max-size`（默认 800）、`overlap`（默认 100）

与 `heading` 策略的主要区别：`heading` 第一刀在 Markdown 标题，`semantic` 第一刀在段落。

### 4.4 ParentChildStrategy（small-to-big 检索）

```
算法：
1. 按 parent-size（默认 1200）滑动窗口将文档切为 Parent 大块
2. 每个 Parent 内部按 child-size（默认 300）+ child-overlap 切为 Child 小块
3. 只将 Child 写入 Milvus，每条 Child 的 metadata 包含：
   - strategy: "parent-child"
   - parentId: UUID（同一 Parent 下的 Child 共享）
   - parentContent: 完整 Parent 文本
   - childIndex / totalChildren
4. 检索时 VectorSearchService 检测 strategy == "parent-child"，
   将 content 替换为 metadata.parentContent，按 parentId 去重
```

**Milvus 存储结构**（每条 Child）：

| 字段 | 值 |
|------|---|
| id | UUID(child-xxx) |
| content | Child 的 ~300 字符文本 |
| vector | Child 的 embedding |
| metadata | `{"strategy":"parent-child", "parentId":"p001", "parentContent":"...1200字...", ...}` |

**检索侧改造**：`VectorSearchService` 新增私有方法 `resolveParentContent(List<SearchResult>): List<SearchResult>`

```java
private List<SearchResult> resolveParentContent(List<SearchResult> results) {
    Set<String> seen = new HashSet<>();
    List<SearchResult> out = new ArrayList<>();
    for (SearchResult r : results) {
        Map<String, Object> meta = parseMetadata(r.getMetadata());
        if ("parent-child".equals(meta.get("strategy"))) {
            String pid = (String) meta.get("parentId");
            if (seen.contains(pid)) continue;        // 去重
            seen.add(pid);
            String pc = (String) meta.get("parentContent");
            if (pc != null) r.setContent(pc);
        }
        out.add(r);
    }
    return out;
}
```

- 注入点到 `denseSearch()` 和 `sparseSearch()` 各自返回前
- 非 parent-child 策略直接穿过，零影响
- RRF 融合前 parent content 已替换完毕，不受影响

## 5. 配置设计

### 5.1 application.yml

```yaml
document:
  chunk:
    # 以下两个属性是旧配置，DocumentChunkConfig 继续使用，HeadingChunkStrategy 沿用
    max-size: 800
    overlap: 100
    # 以下为新增的策略配置
    strategy:
      default-strategy: heading            # 全局默认策略
      extension-overrides:               # 按扩展名覆盖（可选）
        txt: fixed-size
        md: heading
    strategies:                          # 各策略的独立参数
      fixed-size:
        max-size: 500
        overlap: 100
      semantic:
        max-size: 800
        overlap: 100
      parent-child:
        child-size: 300
        parent-size: 1200
        overlap: 50
```

> **配置前缀说明**：旧 `DocumentChunkConfig` 绑定 `document.chunk`（max-size/overlap），新生效的 `ChunkStrategyProperties` 绑定 `document.chunk.strategy`，二者前缀不同，互不冲突。`heading` 策略没有独立配置块，因为它委托给 `DocumentChunkService`，后者直接读 `DocumentChunkConfig`。

### 5.2 配置类

```java
// config/ChunkStrategyProperties.java
@Configuration
@ConfigurationProperties(prefix = "document.chunk.strategy")
public class ChunkStrategyProperties {

    /** 全局默认策略名，默认 heading */
    private String defaultStrategy = "heading";

    /** 各策略的独立配置 */
    private Map<String, StrategyConfig> strategies = new HashMap<>();

    /** 扩展名 → 策略名 覆盖映射 */
    private Map<String, String> extensionOverrides = new HashMap<>();

    @Getter @Setter
    public static class StrategyConfig {
        private int maxSize = 800;
        private int overlap = 100;
        // parent-child 专用
        private Integer childSize;
        private Integer parentSize;
    }
}
```

### 5.3 保留旧配置兼容

现有的 `DocumentChunkConfig`（`document.chunk.max-size` / `document.chunk.overlap`）继续保留，由 `HeadingChunkStrategy` 使用。新增的策略各自读取 `document.chunk.strategies.<name>.*` 下的配置。

## 6. 工厂设计

```java
// service/chunk/ChunkStrategyFactory.java
@Component
public class ChunkStrategyFactory {

    private final Map<String, DocumentChunkStrategy> strategyMap;
    private final ChunkStrategyProperties properties;

    public ChunkStrategyFactory(List<DocumentChunkStrategy> strategies,
                                ChunkStrategyProperties properties) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(DocumentChunkStrategy::strategyName, s -> s));
        this.properties = properties;
        log.info("已注册 {} 个文档切分策略: {}", strategyMap.size(), strategyMap.keySet());
    }

    /**
     * 根据文件扩展名选择策略
     * 1. 查 extension-overrides 是否有该扩展名的配置
     * 2. 否则用 default-strategy
     */
    public DocumentChunkStrategy getStrategy(String fileExtension) {
        String ext = fileExtension != null ? fileExtension.toLowerCase() : "";
        String strategyName = properties.getExtensionOverrides()
            .getOrDefault(ext, properties.getDefaultStrategy());

        DocumentChunkStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("未找到策略 '{}', 降级为 heading", strategyName);
            strategy = strategyMap.get("heading");
        }
        return strategy;
    }
}
```

## 7. VectorIndexService 变更

改动集中在构造函数和 `indexSingleFile()`：

```
变更前：
  private final DocumentChunkService chunkService;
  public VectorIndexService(..., DocumentChunkService chunkService, ...) { ... }
  List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());

变更后：
  private final ChunkStrategyFactory chunkStrategyFactory;
  public VectorIndexService(..., ChunkStrategyFactory chunkStrategyFactory, ...) { ... }
  DocumentChunkStrategy strategy = chunkStrategyFactory.getStrategy(extension);
  List<DocumentChunk> chunks = strategy.chunk(content, path.toString());
```

## 8. 新增文件清单

```
src/main/java/org/example/
├── config/
│   └── ChunkStrategyProperties.java          (新增)
├── service/chunk/
│   ├── DocumentChunkStrategy.java            (新增 - 接口)
│   ├── HeadingChunkStrategy.java             (新增 - 适配器)
│   ├── FixedSizeChunkStrategy.java           (新增)
│   ├── SemanticBoundaryStrategy.java         (新增)
│   ├── ParentChildStrategy.java              (新增)
│   └── ChunkStrategyFactory.java             (新增 - 工厂)
```

## 9. 修改文件清单

| 文件 | 变更内容 |
|------|---------|
| `VectorIndexService.java` | 构造函数注入 `ChunkStrategyFactory` 替代 `DocumentChunkService`；`indexSingleFile()` 通过工厂获取策略 |
| `VectorSearchService.java` | 新增 `resolveParentContent()` 方法；在 `denseSearch()` 和 `sparseSearch()` 返回前调用 |
| `application.yml` | 新增 `document.chunk.default-strategy`、`strategies.*`、`extension-overrides` 配置块 |

**不改的文件**：`DocumentChunkService.java`、`DocumentChunkConfig.java`、`DocumentChunk.java` 均保持不变。

## 10. 测试策略

| 层级 | 内容 |
|------|------|
| 单元测试 | 每个策略独立测试：空文档、纯文本、Markdown、长文档、边界值 |
| 集成测试 | `VectorIndexService` 使用不同策略索引同一文件，验证 Milvus 写入 |
| 检索测试 | Parent-Child 策略：验证 Child 命中后返回 Parent content、去重逻辑 |
| 回归测试 | 默认策略（heading）下，确保与现有行为完全一致 |

## 11. 风险与降级

| 风险 | 应对 |
|------|------|
| 配置了不存在的策略名 | 工厂降级为 `heading`，日志 warn |
| Parent-Child 检索找不到 parentContent | 降级返回 child content，日志 warn |
| 大文档 token 消耗 | 当前所有策略为纯规则，无额外 API 调用 |
