# 文档切分策略模式 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将文档切分从 `DocumentChunkService` 重构为可配置的策略模式，支持 heading / fixed-size / semantic / parent-child 四种策略。

**Architecture:** 接口 `DocumentChunkStrategy` + 4 个 `@Component` 实现 → `ChunkStrategyFactory` 按配置路由 → `VectorIndexService` 消费。现有 `DocumentChunkService` 零改动，通过 `HeadingChunkStrategy` 适配器接入。

**Tech Stack:** Spring Boot 3.2、Spring `@ConfigurationProperties`、现有 DashScope + Milvus 栈不变

## Global Constraints

- 现有 `DocumentChunkService.java` **代码不改一行**
- `DocumentChunkConfig.java` 保持不变
- 默认策略 `heading`，与现有行为完全一致
- 全局默认策略 + 可按文件扩展名覆盖
- 策略均为纯规则策略，无额外 LLM/API 调用

---

## File Structure

```
新增:
  src/main/java/org/example/
  ├── config/ChunkStrategyProperties.java
  ├── service/chunk/
  │   ├── DocumentChunkStrategy.java            (接口)
  │   ├── HeadingChunkStrategy.java             (适配器)
  │   ├── FixedSizeChunkStrategy.java           (固定大小)
  │   ├── SemanticBoundaryStrategy.java         (语义边界)
  │   ├── ParentChildStrategy.java              (parent-child)
  │   └── ChunkStrategyFactory.java             (工厂)

修改:
  src/main/java/org/example/
  ├── dto/DocumentChunk.java                    (新增 extraMetadata 字段)
  ├── service/VectorIndexService.java           (注入工厂替代 DocumentChunkService)
  ├── service/VectorSearchService.java          (新增 resolveParentContent)
  └── resources/application.yml                 (新增策略配置块)
```

---

### Task 1: 接口 + 配置类 + YAML + DocumentChunk 扩展

**Files:**
- Create: `src/main/java/org/example/service/chunk/DocumentChunkStrategy.java`
- Create: `src/main/java/org/example/config/ChunkStrategyProperties.java`
- Modify: `src/main/java/org/example/dto/DocumentChunk.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `org.example.dto.DocumentChunk` (existing)
- Produces: `DocumentChunkStrategy` 接口 (所有策略实现), `ChunkStrategyProperties` (工厂和策略消费), `DocumentChunk.extraMetadata` (VectorIndexService 消费)

- [ ] **Step 1: 创建策略接口**

```java
// src/main/java/org/example/service/chunk/DocumentChunkStrategy.java
package org.example.service.chunk;

import org.example.dto.DocumentChunk;

import java.util.List;

/**
 * 文档切分策略接口
 * 每种切分算法对应一个实现类，负责将文档内容切分为语义片段
 */
public interface DocumentChunkStrategy {

    /**
     * 策略标识，与配置项 strategy-name 对应
     */
    String strategyName();

    /**
     * 执行文档分片
     *
     * @param content  文档纯文本内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    List<DocumentChunk> chunk(String content, String filePath);
}
```

- [ ] **Step 2: 创建配置属性类**

```java
// src/main/java/org/example/config/ChunkStrategyProperties.java
package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档切分策略配置属性
 * 读取 application.yml 中 document.chunk.strategy.* 配置块
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "document.chunk.strategy")
public class ChunkStrategyProperties {

    /** 全局默认策略名，默认 heading */
    private String defaultStrategy = "heading";

    /** 扩展名 → 策略名覆盖映射（不含点号，小写，如 "txt" → "fixed-size"） */
    private Map<String, String> extensionOverrides = new HashMap<>();

    /** 各策略的独立配置参数 */
    private Map<String, StrategyConfig> strategies = new HashMap<>();

    @Getter
    @Setter
    public static class StrategyConfig {
        private int maxSize = 800;
        private int overlap = 100;
        /** parent-child 专用：子块最大字符数 */
        private Integer childSize;
        /** parent-child 专用：父块最大字符数 */
        private Integer parentSize;
    }
}
```

- [ ] **Step 3: DocumentChunk 新增 extraMetadata 字段**

读取 `src/main/java/org/example/dto/DocumentChunk.java`，在 `title` 字段后面新增：

```java
    /**
     * 策略附加的扩展元数据（如 strategy、parentId、parentContent 等）
     * 由策略实现填充，VectorIndexService.buildMetadata() 合并到 Milvus metadata 中
     */
    private Map<String, Object> extraMetadata;
```

并在文件顶部添加 import：

```java
import java.util.Map;
```

- [ ] **Step 4: 更新 application.yml 新增配置块**

读取 `src/main/resources/application.yml`，在 `document.chunk` 配置块末尾（`overlap: 100` 之后）追加：

```yaml
    # === 以下为新增的策略配置 ===
    strategy:
      default-strategy: heading          # heading | fixed-size | semantic | parent-child
      extension-overrides:               # 按扩展名覆盖（可选）
        txt: fixed-size
      strategies:                        # 各策略的独立参数
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

- [ ] **Step 5: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/org/example/service/chunk/DocumentChunkStrategy.java \
        src/main/java/org/example/config/ChunkStrategyProperties.java \
        src/main/java/org/example/dto/DocumentChunk.java \
        src/main/resources/application.yml
git commit -m "feat: add DocumentChunkStrategy interface, config properties, and YAML"
```

---

### Task 2: HeadingChunkStrategy（适配现有逻辑）

**Files:**
- Create: `src/main/java/org/example/service/chunk/HeadingChunkStrategy.java`

**Interfaces:**
- Consumes: `DocumentChunkStrategy` 接口, `DocumentChunkService` (existing @Service)
- Produces: `HeadingChunkStrategy` Bean (strategyName="heading")

- [ ] **Step 1: 创建 HeadingChunkStrategy**

```java
// src/main/java/org/example/service/chunk/HeadingChunkStrategy.java
package org.example.service.chunk;

import org.example.dto.DocumentChunk;
import org.example.service.DocumentChunkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 标题拆分策略（适配器）
 * 委托给现有 DocumentChunkService，不改动原有切分逻辑
 */
@Component
public class HeadingChunkStrategy implements DocumentChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(HeadingChunkStrategy.class);

    @Autowired
    private DocumentChunkService delegate;

    @Override
    public String strategyName() {
        return "heading";
    }

    @Override
    public List<DocumentChunk> chunk(String content, String filePath) {
        logger.debug("使用 heading 策略切分: {}", filePath);
        return delegate.chunkDocument(content, filePath);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/example/service/chunk/HeadingChunkStrategy.java
git commit -m "feat: add HeadingChunkStrategy adapter for existing DocumentChunkService"
```

---

### Task 3: FixedSizeChunkStrategy（固定大小 + 重叠）

**Files:**
- Create: `src/main/java/org/example/service/chunk/FixedSizeChunkStrategy.java`

**Interfaces:**
- Consumes: `DocumentChunkStrategy` 接口, `ChunkStrategyProperties` (读取 strategies.fixed-size 配置)
- Produces: `FixedSizeChunkStrategy` Bean (strategyName="fixed-size")

- [ ] **Step 1: 创建 FixedSizeChunkStrategy**

```java
// src/main/java/org/example/service/chunk/FixedSizeChunkStrategy.java
package org.example.service.chunk;

import org.example.config.ChunkStrategyProperties;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定大小切分策略
 * 按固定窗口 + 重叠滑动切割，不感知文档标题、段落、句子结构
 */
@Component
public class FixedSizeChunkStrategy implements DocumentChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(FixedSizeChunkStrategy.class);

    private final ChunkStrategyProperties properties;

    public FixedSizeChunkStrategy(ChunkStrategyProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "fixed-size";
    }

    @Override
    public List<DocumentChunk> chunk(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        ChunkStrategyProperties.StrategyConfig config = getConfig();
        int maxSize = config.getMaxSize();
        int overlap = config.getOverlap();
        int step = Math.max(1, maxSize - overlap); // 确保步长至少为 1
        int pos = 0;
        int chunkIndex = 0;

        while (pos < content.length()) {
            int end = Math.min(pos + maxSize, content.length());
            String chunkContent = content.substring(pos, end);

            DocumentChunk chunk = new DocumentChunk(chunkContent, pos, end, chunkIndex);
            chunks.add(chunk);

            chunkIndex++;

            if (end >= content.length()) {
                break;
            }

            pos += step;
        }

        logger.info("fixed-size 策略切分完成: {} -> {} 个分片 (maxSize={}, overlap={})",
                filePath, chunks.size(), maxSize, overlap);
        return chunks;
    }

    private ChunkStrategyProperties.StrategyConfig getConfig() {
        ChunkStrategyProperties.StrategyConfig config =
                properties.getStrategies().get(strategyName());
        if (config == null) {
            logger.warn("未找到 fixed-size 策略配置，使用默认值 maxSize=500, overlap=100");
            ChunkStrategyProperties.StrategyConfig defaultConfig =
                    new ChunkStrategyProperties.StrategyConfig();
            defaultConfig.setMaxSize(500);
            defaultConfig.setOverlap(100);
            return defaultConfig;
        }
        return config;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/example/service/chunk/FixedSizeChunkStrategy.java
git commit -m "feat: add FixedSizeChunkStrategy with sliding window + overlap"
```

---

### Task 4: SemanticBoundaryStrategy（语义边界切割）

**Files:**
- Create: `src/main/java/org/example/service/chunk/SemanticBoundaryStrategy.java`

**Interfaces:**
- Consumes: `DocumentChunkStrategy` 接口, `ChunkStrategyProperties`
- Produces: `SemanticBoundaryStrategy` Bean (strategyName="semantic")

- [ ] **Step 1: 创建 SemanticBoundaryStrategy**

```java
// src/main/java/org/example/service/chunk/SemanticBoundaryStrategy.java
package org.example.service.chunk;

import org.example.config.ChunkStrategyProperties;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义边界切分策略
 * 按段落边界（\\n\\n+）优先切分，单段落超长时在句子边界切分
 */
@Component
public class SemanticBoundaryStrategy implements DocumentChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(SemanticBoundaryStrategy.class);

    private final ChunkStrategyProperties properties;

    public SemanticBoundaryStrategy(ChunkStrategyProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "semantic";
    }

    @Override
    public List<DocumentChunk> chunk(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        ChunkStrategyProperties.StrategyConfig config = getConfig();
        int maxSize = config.getMaxSize();
        int overlap = config.getOverlap();

        // 1. 按空行（段落）粗切
        List<String> paragraphs = splitByParagraphs(content);

        // 2. 逐段落拼接成 chunk
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = 0;
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            // 如果当前 chunk 加上新段落超过最大尺寸
            if (currentChunk.length() > 0
                    && currentChunk.length() + paragraph.length() > maxSize) {

                String chunkContent = currentChunk.toString().trim();
                DocumentChunk chunk = new DocumentChunk(
                        chunkContent, currentStartIndex,
                        currentStartIndex + chunkContent.length(), chunkIndex);
                chunks.add(chunk);
                chunkIndex++;

                // 新 chunk 以 overlap 文本开头，对齐句子边界
                String overlapText = getOverlapAlignedToSentence(chunkContent, overlap);
                currentChunk = new StringBuilder(overlapText);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlapText.length();
            }

            // 如果单个段落超过 maxSize，按句子边界切分该段落
            if (paragraph.length() > maxSize) {
                List<String> subChunks = splitLongParagraph(
                        paragraph, maxSize, overlap,
                        currentStartIndex + currentChunk.length(),
                        chunkIndex);
                chunks.addAll(subChunks);
                chunkIndex += subChunks.size();
                // 重置 currentChunk 为新段落开头
                String lastChunkContent = subChunks.get(subChunks.size() - 1).getContent();
                String overlapText = getOverlapAlignedToSentence(lastChunkContent, overlap);
                currentChunk = new StringBuilder(overlapText);
                currentStartIndex = currentStartIndex + paragraph.length() - overlapText.length();
            } else {
                currentChunk.append(paragraph).append("\n\n");
            }
        }

        // 保存最后一个 chunk
        if (currentChunk.length() > 0) {
            String chunkContent = currentChunk.toString().trim();
            DocumentChunk chunk = new DocumentChunk(
                    chunkContent, currentStartIndex,
                    currentStartIndex + chunkContent.length(), chunkIndex);
            chunks.add(chunk);
        }

        logger.info("semantic 策略切分完成: {} -> {} 个分片 (maxSize={}, overlap={})",
                filePath, chunks.size(), maxSize, overlap);
        return chunks;
    }

    /**
     * 按双换行符分割段落
     */
    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = content.split("\n\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /**
     * 将超长段落按句子边界切分为多个子块
     */
    private List<DocumentChunk> splitLongParagraph(String paragraph, int maxSize,
                                                    int overlap, int baseOffset,
                                                    int startChunkIndex) {
        List<DocumentChunk> subChunks = new ArrayList<>();
        int pos = 0;
        int chunkIdx = startChunkIndex;

        while (pos < paragraph.length()) {
            int end = Math.min(pos + maxSize, paragraph.length());

            // 尝试在 maxSize 附近找最近的句子边界
            if (end < paragraph.length()) {
                int boundary = findLastSentenceBoundary(paragraph, pos, end);
                if (boundary > pos + maxSize / 2) {
                    end = boundary + 1;
                }
            }

            String subContent = paragraph.substring(pos, end);
            DocumentChunk chunk = new DocumentChunk(
                    subContent, baseOffset + pos, baseOffset + end, chunkIdx);
            subChunks.add(chunk);
            chunkIdx++;

            if (end >= paragraph.length()) {
                break;
            }

            // 计算下一个起始位置（含 overlap）
            int nextPos = end - overlap;
            if (nextPos <= pos) {
                nextPos = pos + 1; // 确保前进
            }
            pos = nextPos;
        }

        return subChunks;
    }

    /**
     * 在 [start, end] 范围内找最后一个句子边界
     */
    private int findLastSentenceBoundary(String text, int start, int end) {
        for (int i = end; i >= start; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n'
                    || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return end; // 没找到边界，返回 end
    }

    /**
     * 从文本末尾提取 overlap 字符，对齐句子边界
     */
    private String getOverlapAlignedToSentence(String text, int overlapSize) {
        int size = Math.min(overlapSize, text.length());
        if (size <= 0) return "";

        String overlap = text.substring(text.length() - size);
        int lastBoundary = -1;
        for (int i = overlap.length() - 1; i >= overlap.length() / 2; i--) {
            char c = overlap.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n'
                    || c == '.' || c == '!' || c == '?') {
                lastBoundary = i;
                break;
            }
        }

        if (lastBoundary > 0) {
            return overlap.substring(lastBoundary + 1).trim();
        }
        return overlap.trim();
    }

    private ChunkStrategyProperties.StrategyConfig getConfig() {
        ChunkStrategyProperties.StrategyConfig config =
                properties.getStrategies().get(strategyName());
        if (config == null) {
            logger.warn("未找到 semantic 策略配置，使用默认值 maxSize=800, overlap=100");
            ChunkStrategyProperties.StrategyConfig defaultConfig =
                    new ChunkStrategyProperties.StrategyConfig();
            defaultConfig.setMaxSize(800);
            defaultConfig.setOverlap(100);
            return defaultConfig;
        }
        return config;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/example/service/chunk/SemanticBoundaryStrategy.java
git commit -m "feat: add SemanticBoundaryStrategy with paragraph + sentence boundary splitting"
```

---

### Task 5: ParentChildStrategy（small-to-big 检索）

**Files:**
- Create: `src/main/java/org/example/service/chunk/ParentChildStrategy.java`

**Interfaces:**
- Consumes: `DocumentChunkStrategy` 接口, `ChunkStrategyProperties`
- Produces: `ParentChildStrategy` Bean (strategyName="parent-child")，每个 chunk 的 extraMetadata 含 `strategy`、`parentId`、`parentContent`、`childIndex`、`totalChildren`

- [ ] **Step 1: 创建 ParentChildStrategy**

```java
// src/main/java/org/example/service/chunk/ParentChildStrategy.java
package org.example.service.chunk;

import org.example.config.ChunkStrategyProperties;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Parent-Child 切分策略（small-to-big 检索）
 * 将文档切为 Parent 大块（约 1200 字符），每个 Parent 再切成 Child 小块（约 300 字符）。
 * 只返回 Child chunk，每条 Child 的 extraMetadata 包含完整 Parent 内容。
 * 检索时用 Child 匹配向量，返回对应 Parent 内容。
 */
@Component
public class ParentChildStrategy implements DocumentChunkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(ParentChildStrategy.class);

    private final ChunkStrategyProperties properties;

    public ParentChildStrategy(ChunkStrategyProperties properties) {
        this.properties = properties;
    }

    @Override
    public String strategyName() {
        return "parent-child";
    }

    @Override
    public List<DocumentChunk> chunk(String content, String filePath) {
        List<DocumentChunk> allChildren = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return allChildren;
        }

        ChunkStrategyProperties.StrategyConfig config = getConfig();
        int parentSize = config.getParentSize() != null ? config.getParentSize() : 1200;
        int childSize = config.getChildSize() != null ? config.getChildSize() : 300;
        int overlap = config.getOverlap();

        // 1. 滑动窗口切 Parent 大块
        int parentStep = Math.max(1, parentSize - overlap);
        int pos = 0;
        int globalChildIndex = 0;

        while (pos < content.length()) {
            int parentEnd = Math.min(pos + parentSize, content.length());
            String parentContent = content.substring(pos, parentEnd);
            String parentId = UUID.randomUUID().toString();

            // 2. 每个 Parent 内部切 Child 小块
            List<DocumentChunk> children = splitChildren(
                    parentContent, parentId, childSize, overlap,
                    pos, globalChildIndex);
            allChildren.addAll(children);
            globalChildIndex += children.size();

            if (parentEnd >= content.length()) {
                break;
            }
            pos += parentStep;
        }

        logger.info("parent-child 策略切分完成: {} -> {} 个 Parent, {} 个 Child (parentSize={}, childSize={})",
                filePath, globalChildIndex > 0 ? "N" : "0", allChildren.size(), parentSize, childSize);
        return allChildren;
    }

    /**
     * 将一段 Parent 内容切为多个 Child 小块
     */
    private List<DocumentChunk> splitChildren(String parentContent, String parentId,
                                               int childSize, int overlap,
                                               int parentOffset, int startChunkIndex) {
        List<DocumentChunk> children = new ArrayList<>();
        int childStep = Math.max(1, childSize - overlap);
        int pos = 0;
        int chunkIndex = startChunkIndex;

        while (pos < parentContent.length()) {
            int childEnd = Math.min(pos + childSize, parentContent.length());
            String childContent = parentContent.substring(pos, childEnd);

            DocumentChunk child = new DocumentChunk(
                    childContent,
                    parentOffset + pos,
                    parentOffset + childEnd,
                    chunkIndex);

            // 填充 extraMetadata：strategy, parentId, parentContent, childIndex, totalChildren
            Map<String, Object> meta = new HashMap<>();
            meta.put("strategy", "parent-child");
            meta.put("parentId", parentId);
            meta.put("parentContent", parentContent);
            child.setExtraMetadata(meta);

            children.add(child);
            chunkIndex++;

            if (childEnd >= parentContent.length()) {
                break;
            }
            pos += childStep;
        }

        // 回填 totalChildren（此时已知总数）
        int totalChildren = children.size();
        for (DocumentChunk child : children) {
            child.getExtraMetadata().put("childIndex", child.getChunkIndex() - startChunkIndex);
            child.getExtraMetadata().put("totalChildren", totalChildren);
        }

        return children;
    }

    private ChunkStrategyProperties.StrategyConfig getConfig() {
        ChunkStrategyProperties.StrategyConfig config =
                properties.getStrategies().get(strategyName());
        if (config == null) {
            logger.warn("未找到 parent-child 策略配置，使用默认值 parentSize=1200, childSize=300, overlap=50");
            ChunkStrategyProperties.StrategyConfig defaultConfig =
                    new ChunkStrategyProperties.StrategyConfig();
            defaultConfig.setParentSize(1200);
            defaultConfig.setChildSize(300);
            defaultConfig.setOverlap(50);
            return defaultConfig;
        }
        return config;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/example/service/chunk/ParentChildStrategy.java
git commit -m "feat: add ParentChildStrategy for small-to-big retrieval"
```

---

### Task 6: ChunkStrategyFactory（路由工厂）

**Files:**
- Create: `src/main/java/org/example/service/chunk/ChunkStrategyFactory.java`

**Interfaces:**
- Consumes: `List<DocumentChunkStrategy>` (Spring 自动收集所有 @Component), `ChunkStrategyProperties`
- Produces: `ChunkStrategyFactory.getStrategy(String fileExtension)` → `DocumentChunkStrategy`

- [ ] **Step 1: 创建 ChunkStrategyFactory**

```java
// src/main/java/org/example/service/chunk/ChunkStrategyFactory.java
package org.example.service.chunk;

import org.example.config.ChunkStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档切分策略工厂
 * 根据文件扩展名和配置选择对应的切分策略
 */
@Component
public class ChunkStrategyFactory {

    private static final Logger logger = LoggerFactory.getLogger(ChunkStrategyFactory.class);

    private final Map<String, DocumentChunkStrategy> strategyMap;
    private final ChunkStrategyProperties properties;

    public ChunkStrategyFactory(List<DocumentChunkStrategy> strategies,
                                ChunkStrategyProperties properties) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        DocumentChunkStrategy::strategyName,
                        s -> s,
                        (existing, replacement) -> {
                            logger.warn("策略名冲突: {} 已存在，后者覆盖", existing.strategyName());
                            return replacement;
                        }));
        this.properties = properties;
        logger.info("已注册 {} 个文档切分策略: {}", strategyMap.size(), strategyMap.keySet());
    }

    /**
     * 根据文件扩展名选择策略
     * 优先查 extension-overrides，未配置则使用 default-strategy
     *
     * @param fileExtension 文件扩展名（不含点号，如 "md"、"txt"），可为 null
     * @return 对应的切分策略，保证非 null
     */
    public DocumentChunkStrategy getStrategy(String fileExtension) {
        String ext = fileExtension != null ? fileExtension.toLowerCase().trim() : "";
        String strategyName = properties.getExtensionOverrides()
                .getOrDefault(ext, properties.getDefaultStrategy());

        DocumentChunkStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            logger.warn("未找到策略 '{}', 降级为 heading", strategyName);
            strategy = strategyMap.get("heading");
        }

        if (strategy == null) {
            throw new IllegalStateException(
                    "无可用的文档切分策略，请确保至少注册了 heading 策略");
        }

        return strategy;
    }

    /**
     * 获取当前默认策略名（用于日志/调试）
     */
    public String getDefaultStrategyName() {
        return properties.getDefaultStrategy();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/example/service/chunk/ChunkStrategyFactory.java
git commit -m "feat: add ChunkStrategyFactory for routing by extension and config"
```

---

### Task 7: VectorIndexService 重构为使用工厂

**Files:**
- Modify: `src/main/java/org/example/service/VectorIndexService.java`

**Interfaces:**
- Consumes: `ChunkStrategyFactory`
- Produces: 无接口变更，`indexSingleFile()` 行为保持一致

- [ ] **Step 1: 修改构造函数和字段，替换 DocumentChunkService 为 ChunkStrategyFactory**

读取 `VectorIndexService.java` 全文。执行以下变更：

**字段替换**（约第 39 行）：
```java
// 旧：
    private final DocumentChunkService chunkService;
// 新：
    private final ChunkStrategyFactory chunkStrategyFactory;
```

**import 替换**（文件顶部）：
```java
// 删除：
import org.example.service.DocumentChunkService;
// 新增：
import org.example.service.chunk.ChunkStrategyFactory;
import org.example.service.chunk.DocumentChunkStrategy;
```

**构造函数替换**（约第 49-63 行）：
```java
// 旧：
    public VectorIndexService(MilvusServiceClient milvusClient,
                              VectorEmbeddingService embeddingService,
                              DocumentChunkService chunkService,
                              List<DocumentParser> parsers) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.chunkService = chunkService;
// 新：
    public VectorIndexService(MilvusServiceClient milvusClient,
                              VectorEmbeddingService embeddingService,
                              ChunkStrategyFactory chunkStrategyFactory,
                              List<DocumentParser> parsers) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.chunkStrategyFactory = chunkStrategyFactory;
```

- [ ] **Step 2: 修改 indexSingleFile() 中的分片调用**

在 `indexSingleFile()` 方法中（约第 170-172 行），将：
```java
        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
```

替换为：
```java
        // 3. 文档分片（通过策略工厂选择策略）
        DocumentChunkStrategy strategy = chunkStrategyFactory.getStrategy(extension);
        logger.info("使用策略 '{}' 切分文件: {}", strategy.strategyName(), filePath);
        List<DocumentChunk> chunks = strategy.chunk(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
```

- [ ] **Step 3: 修改 buildMetadata() 合并 extraMetadata**

在 `buildMetadata()` 方法 return 语句之前（约第 271 行），添加 extraMetadata 合并逻辑：

```java
        // 合并策略附加的扩展元数据（如 parent-child 的 parentId、parentContent 等）
        if (chunk.getExtraMetadata() != null) {
            metadata.putAll(chunk.getExtraMetadata());
        }
```

- [ ] **Step 4: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/org/example/service/VectorIndexService.java
git commit -m "refactor: VectorIndexService uses ChunkStrategyFactory instead of DocumentChunkService"
```

---

### Task 8: VectorSearchService 新增 parent-child resolve

**Files:**
- Modify: `src/main/java/org/example/service/VectorSearchService.java`

**Interfaces:**
- Consumes: 无新依赖，新增内部私有方法
- Produces: `resolveParentContent()` 在 denseSearch/sparseSearch 返回前调用

- [ ] **Step 1: 新增 import 和工具方法**

在 VectorSearchService 顶部 import 区域新增：
```java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.HashSet;
```

在类内新增两个私有方法（放在 `rrfFusion()` 方法之后）：

```java
    /**
     * 解析 parent-child 策略的检索结果
     * 检测 strategy == "parent-child" 时，将 content 替换为 parentContent，按 parentId 去重
     */
    private List<SearchResult> resolveParentContent(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return results;

        Set<String> seenParentIds = new HashSet<>();
        List<SearchResult> resolved = new ArrayList<>();

        for (SearchResult r : results) {
            if (r.getMetadata() == null || r.getMetadata().isEmpty()) {
                resolved.add(r);
                continue;
            }

            try {
                java.util.Map<String, Object> meta = parseMetadata(r.getMetadata());
                if (!"parent-child".equals(meta.get("strategy"))) {
                    resolved.add(r);
                    continue;
                }

                // parent-child 策略：去重 + 替换为 parent content
                String parentId = (String) meta.get("parentId");
                if (parentId != null && !parentId.isEmpty()) {
                    if (seenParentIds.contains(parentId)) {
                        logger.debug("parent-child 去重: parentId={}", parentId);
                        continue;
                    }
                    seenParentIds.add(parentId);
                }

                String parentContent = (String) meta.get("parentContent");
                if (parentContent != null && !parentContent.isEmpty()) {
                    logger.debug("parent-child 替换: child content ({} 字符) → parent content ({} 字符)",
                            r.getContent() != null ? r.getContent().length() : 0,
                            parentContent.length());
                    r.setContent(parentContent);
                } else {
                    logger.warn("parent-child 策略未找到 parentContent，降级使用 child content");
                }

            } catch (Exception e) {
                logger.warn("解析 parent-child metadata 失败，保留原始 content: {}", e.getMessage());
            }

            resolved.add(r);
        }

        if (resolved.size() < results.size()) {
            logger.info("parent-child 去重: {} → {} 条结果", results.size(), resolved.size());
        }
        return resolved;
    }

    /**
     * 将 metadata JSON 字符串解析为 Map
     */
    private java.util.Map<String, Object> parseMetadata(String metadataJson) {
        Gson gson = new Gson();
        return gson.fromJson(metadataJson,
                new TypeToken<java.util.Map<String, Object>>() {}.getType());
    }
```

- [ ] **Step 2: 在 denseSearch() 返回前调用 resolveParentContent**

在 `denseSearch()` 方法（约第 229 行），`logger.info("向量检索召回 {} 个文档", results.size());` 之后、`return results;` 之前，插入：

```java
        // 处理 parent-child 策略的 small-to-big 检索
        results = resolveParentContent(results);
```

- [ ] **Step 3: 在 sparseSearch() 返回前调用 resolveParentContent**

在 `sparseSearch()` 方法（约第 289 行），`logger.info("BM25 稀疏召回 {} 个文档", results.size());` 之后、`return results;` 之前，插入：

```java
        // 处理 parent-child 策略的 small-to-big 检索
        results = resolveParentContent(results);
```

- [ ] **Step 4: 编译验证**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/org/example/service/VectorSearchService.java
git commit -m "feat: add resolveParentContent for parent-child small-to-big retrieval"
```

---

### Task 9: 完整构建 + 回归验证

**Files:**
- 无代码变更，纯验证

- [ ] **Step 1: 完整编译**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行现有测试**

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn test -q
```

Expected: 所有现有测试通过（默认 heading 策略行为不变）

- [ ] **Step 3: 确认所有策略 Bean 已注册**

检查启动日志中是否有类似：
```
已注册 4 个文档切分策略: [heading, fixed-size, semantic, parent-child]
```

```bash
cd "D:\学习\SuperBizAgent\SuperBizAgent-release-2026-05-17" && mvn spring-boot:run 2>&1 | grep "已注册.*文档切分策略"
```

（启动后 Ctrl+C 终止）

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "chore: final verification of chunk strategy pattern implementation"
```

---

## Post-Implementation Verification Checklist

- [ ] `mvn clean compile` 通过
- [ ] 现有测试全部通过（heading 策略行为不变）
- [ ] `application.yml` 中可切换 `default-strategy`
- [ ] `extension-overrides` 按扩展名路由生效
- [ ] Parent-child 策略：检索返回 parent content，多个 child 去重
- [ ] 不存在的策略名降级为 heading（日志 warn）
