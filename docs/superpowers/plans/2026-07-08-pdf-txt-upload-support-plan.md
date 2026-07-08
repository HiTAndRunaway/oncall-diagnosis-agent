# TXT 和 PDF 文件上传支持 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文件上传和文档切分支持 .txt 和 .pdf 格式，通过策略模式实现可扩展的文档解析架构。

**Architecture:** 新增 `DocumentParser` 接口 + `TextDocumentParser`（txt/md）和 `PdfDocumentParser`（pdf/PDFBox）两个实现类。`VectorIndexService` 通过构造函数注入所有 Parser，根据扩展名选择解析器，解析后走现有 chunk → embedding → Milvus 流水线不变。

**Tech Stack:** Java 17, Spring Boot 3.2, Apache PDFBox 3.0.3, Maven

**Source root:** `SuperBizAgent-release-2026-05-17/`

## Global Constraints

- Java 17，无新增语言特性要求
- 新增文件放在 `src/main/java/org/example/service/parser/` 包下
- 扫描件 PDF（提取文本 ≤ 50 字符）记录 warn 日志，返回空字符串，不抛异常
- 加密 PDF 抛 `DocumentParseException`
- 前端 accept 和 JS 校验必须与后端允许的扩展名一致
- 提交信息使用中文，格式：`feat: <简短描述>`

---

### Task 1: 添加 PDFBox Maven 依赖

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/pom.xml`

**Interfaces:**
- Produces: PDFBox 3.0.3 可用，`org.apache.pdfbox.pdmodel.PDDocument`、`org.apache.pdfbox.text.PDFTextStripper` 可 import

- [ ] **Step 1: 在 pom.xml 的 `<dependencies>` 块末尾（`</dependencies>` 之前）添加 PDFBox 依赖**

打开 `SuperBizAgent-release-2026-05-17/pom.xml`，找到第 152 行附近的 `</dependencies>` 闭合标签，在其前插入：

```xml
        <!-- Apache PDFBox - PDF 文本提取 -->
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>3.0.3</version>
        </dependency>
```

- [ ] **Step 2: 验证依赖下载成功**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn dependency:resolve -DincludeArtifactIds=pdfbox 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`，无错误。

- [ ] **Step 3: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/pom.xml && git commit -m "build: 添加 PDFBox 3.0.3 依赖"
```

---

### Task 2: 创建 DocumentParseException

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/DocumentParseException.java`

**Interfaces:**
- Produces: `DocumentParseException extends RuntimeException`，两个构造器 `(String message)` 和 `(String message, Throwable cause)`

- [ ] **Step 1: 创建目录**

```bash
mkdir -p "SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser"
```

- [ ] **Step 2: 创建 DocumentParseException**

```java
package org.example.service.parser;

/**
 * 文档解析异常，表示文件无法被解析（加密、损坏、格式不支持等）
 * 继承 RuntimeException，由上层统一异常处理兜底
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/DocumentParseException.java && git commit -m "feat: 新增 DocumentParseException"
```

---

### Task 3: 创建 DocumentParser 接口

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/DocumentParser.java`

**Interfaces:**
- Produces: `DocumentParser` 接口 — `List<String> supportedExtensions()` + `String parse(Path) throws DocumentParseException`

- [ ] **Step 1: 创建 DocumentParser**

```java
package org.example.service.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析器接口
 * 每种文件格式对应一个实现类，负责从文件中提取纯文本内容
 */
public interface DocumentParser {

    /**
     * 返回该解析器支持的文件扩展名列表
     * 扩展名不含点号，小写，如 ["pdf"] 或 ["txt", "md", "markdown"]
     */
    List<String> supportedExtensions();

    /**
     * 解析文件，提取纯文本内容
     *
     * @param filePath 文件路径
     * @return 提取的纯文本；若文件为空或无可提取文本，返回空字符串
     * @throws DocumentParseException 解析失败（加密、损坏、格式不支持等）
     */
    String parse(Path filePath) throws DocumentParseException;
}
```

- [ ] **Step 2: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/DocumentParser.java && git commit -m "feat: 新增 DocumentParser 接口"
```

---

### Task 4: 创建 TextDocumentParser

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/TextDocumentParser.java`

**Interfaces:**
- Implements: `DocumentParser`
- Consumes: `DocumentParser.supportedExtensions()`, `DocumentParser.parse(Path)`
- Produces: `TextDocumentParser` — 支持 txt / md / markdown，用 `Files.readString()` 读取

- [ ] **Step 1: 创建 TextDocumentParser**

```java
package org.example.service.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 文本文件解析器
 * 支持 .txt、.md、.markdown 格式，使用 UTF-8 编码读取
 */
@Component
public class TextDocumentParser implements DocumentParser {

    private static final Logger logger = LoggerFactory.getLogger(TextDocumentParser.class);

    private static final List<String> EXTENSIONS = Arrays.asList("txt", "md", "markdown");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String parse(Path filePath) throws DocumentParseException {
        try {
            String content = Files.readString(filePath);
            logger.debug("文本文件解析完成: {}, 字符数: {}", filePath.getFileName(), content.length());
            return content;
        } catch (Exception e) {
            throw new DocumentParseException("读取文本文件失败: " + filePath.getFileName(), e);
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/TextDocumentParser.java && git commit -m "feat: 新增 TextDocumentParser 支持 txt/md/markdown"
```

---

### Task 5: 创建 PdfDocumentParser

**Files:**
- Create: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/PdfDocumentParser.java`

**Interfaces:**
- Implements: `DocumentParser`
- Consumes: `DocumentParser.supportedExtensions()`, `DocumentParser.parse(Path)`, PDFBox `PDDocument`, `PDFTextStripper`
- Produces: `PdfDocumentParser` — 支持 pdf，用 PDFBox 提取文本，含加密检测和扫描件检测

- [ ] **Step 1: 创建 PdfDocumentParser**

```java
package org.example.service.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * PDF 文件解析器
 * 使用 Apache PDFBox 提取文本层内容
 * 加密 PDF 抛出异常，扫描件（无文本层）返回空字符串并记录 warn 日志
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger logger = LoggerFactory.getLogger(PdfDocumentParser.class);

    private static final List<String> EXTENSIONS = Collections.singletonList("pdf");

    /** 扫描件判定阈值：提取文本 ≤ 此值视为扫描件 */
    private static final int SCANNED_THRESHOLD = 50;

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String parse(Path filePath) throws DocumentParseException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {

            // 加密检测：PDFBox 会在 loadPDF 阶段检测加密，
            // 如果文档已加密且未提供密码，访问页面时会抛出异常
            if (document.isEncrypted()) {
                throw new DocumentParseException("PDF 已加密，无法解析: " + filePath.getFileName());
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            if (text == null || text.trim().isEmpty()) {
                logger.warn("PDF 无文本层，可能为扫描件: {}", filePath.getFileName());
                return "";
            }

            // 扫描件检测：文本过短，可能只是页眉页脚
            if (text.trim().length() <= SCANNED_THRESHOLD) {
                logger.warn("PDF 提取文本过短({}字符)，可能为扫描件: {}", text.trim().length(), filePath.getFileName());
                return "";
            }

            logger.debug("PDF 解析完成: {}, 字符数: {}", filePath.getFileName(), text.length());
            return text;

        } catch (DocumentParseException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentParseException("PDF 文件读取失败: " + filePath.getFileName(), e);
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/parser/PdfDocumentParser.java && git commit -m "feat: 新增 PdfDocumentParser 支持 PDF 文本提取"
```

---

### Task 6: 改造 VectorIndexService 使用 Parser

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/VectorIndexService.java`

**Interfaces:**
- Consumes: `DocumentParser` 接口及两个实现类（Spring 自动注入 `List<DocumentParser>`）
- Produces: `VectorIndexService` 不再硬编码文件读取逻辑，通过 `parserMap` 分发

- [ ] **Step 1: 替换 import 区和字段声明**

**原代码（第 1-46 行）：**

```java
package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;
```

**改为：**

```java
package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.service.parser.DocumentParseException;
import org.example.service.parser.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 * 通过 DocumentParser 策略模式支持多种文件格式
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final DocumentChunkService chunkService;
    private final Map<String, DocumentParser> parserMap;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 构造函数注入所有依赖和 DocumentParser 实现
     * Spring 自动收集所有实现了 DocumentParser 接口的 Bean
     */
    public VectorIndexService(MilvusServiceClient milvusClient,
                              VectorEmbeddingService embeddingService,
                              DocumentChunkService chunkService,
                              List<DocumentParser> parsers) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.chunkService = chunkService;
        this.parserMap = new HashMap<>();
        for (DocumentParser parser : parsers) {
            for (String ext : parser.supportedExtensions()) {
                parserMap.put(ext.toLowerCase(), parser);
            }
        }
        logger.info("已注册 {} 个文档解析器, 支持扩展名: {}", parsers.size(), parserMap.keySet());
    }
```

- [ ] **Step 2: 修改 `indexSingleFile()` 中的文件读取逻辑**

**原代码（约第 135 行）：**

```java
        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());
```

**改为：**

```java
        // 1. 根据扩展名选择 Parser 读取文件内容
        String fileName = path.getFileName().toString();
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex + 1).toLowerCase();
        }

        DocumentParser parser = parserMap.get(extension);
        if (parser == null) {
            throw new DocumentParseException("不支持的文件格式: ." + extension);
        }

        String content = parser.parse(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());
```

- [ ] **Step 3: 修改 `indexDirectory()` 中的扩展名过滤**

**原代码（约第 73-74 行）：**

```java
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );
```

**改为：**

```java
            File[] files = directory.listFiles((dir, name) -> 
                parserMap.keySet().stream().anyMatch(ext -> name.toLowerCase().endsWith("." + ext))
            );
```

- [ ] **Step 4: 验证编译**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn compile 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/src/main/java/org/example/service/VectorIndexService.java && git commit -m "refactor: VectorIndexService 改用 DocumentParser 策略模式读取文件"
```

---

### Task 7: 更新后端配置和前端

**Files:**
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/application.yml`
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/static/index.html`
- Modify: `SuperBizAgent-release-2026-05-17/src/main/resources/static/app.js`

**Interfaces:**
- Consumes: 其他任务产出均已完成
- Produces: 前后端扩展名配置一致 (`txt,md,pdf`)

- [ ] **Step 1: 修改 application.yml**

**原代码（第 12 行）：**

```yaml
    allowed-extensions: txt,md
```

**改为：**

```yaml
    allowed-extensions: txt,md,pdf
```

- [ ] **Step 2: 修改 index.html 文件选择器 accept 属性**

**原代码（第 115 行）：**

```html
                    <input type="file" id="fileInput" accept=".txt,.md,.markdown" style="display: none;">
```

**改为：**

```html
                    <input type="file" id="fileInput" accept=".txt,.md,.markdown,.pdf" style="display: none;">
```

- [ ] **Step 3: 修改 app.js validateFileType()**

**原代码（第 1039-1042 行）：**

```javascript
    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        const allowedExtensions = ['.txt', '.md', '.markdown'];
        return allowedExtensions.some(ext => fileName.endsWith(ext));
    }
```

**改为：**

```javascript
    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        const allowedExtensions = ['.txt', '.md', '.markdown', '.pdf'];
        return allowedExtensions.some(ext => fileName.endsWith(ext));
    }
```

- [ ] **Step 4: 修改 app.js 错误提示文案（两处）**

**第 1030 行 — handleFileSelect 中的提示：**

原：`'只支持上传 TXT 或 Markdown (.md) 格式的文件'`

改为：`'只支持上传 TXT、Markdown (.md) 或 PDF 格式的文件'`

**第 1049 行 — uploadFile 中的提示：**

原：`'只支持上传 TXT 或 Markdown (.md) 格式的文件'`

改为：`'只支持上传 TXT、Markdown (.md) 或 PDF 格式的文件'`

- [ ] **Step 5: 提交**

```bash
cd "D:\学习\SuperBizAgent" && git add SuperBizAgent-release-2026-05-17/src/main/resources/application.yml SuperBizAgent-release-2026-05-17/src/main/resources/static/index.html SuperBizAgent-release-2026-05-17/src/main/resources/static/app.js && git commit -m "feat: 前端和后端配置添加 PDF 格式支持"
```

---

### Task 8: 构建和运行验证

**Files:**
- 验证所有已修改文件

**Interfaces:**
- Consumes: 所有前置任务

- [ ] **Step 1: 完整构建**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn clean install 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: 启动应用并检查健康状态**

```bash
cd SuperBizAgent-release-2026-05-17 && mvn spring-boot:run &
```

等待约 30 秒启动，然后检查：

```bash
curl -s http://localhost:9900/milvus/health
```

Expected: 返回健康状态 JSON，status 正常。

- [ ] **Step 3: 测试 txt 文件上传**

准备一个测试用的 txt 文件：

```bash
echo -e "这是第一段测试内容。\n\n这是第二段测试内容，用于验证 txt 文件上传和分片功能。" > /tmp/test.txt
```

上传：

```bash
curl -s -X POST http://localhost:9900/api/upload -F "file=@/tmp/test.txt" | python3 -m json.tool 2>/dev/null || curl -s -X POST http://localhost:9900/api/upload -F "file=@/tmp/test.txt"
```

Expected: 返回 `"code":200` 和 `"message":"success"`，日志显示 "文档分片完成"。

- [ ] **Step 4: 测试不支持的文件扩展名被拒绝**

```bash
echo "test" > /tmp/test.xyz
curl -s -X POST http://localhost:9900/api/upload -F "file=@/tmp/test.xyz"
```

Expected: 返回 400，消息包含 "不支持的文件格式"。

- [ ] **Step 5: 停止应用**

```bash
pkill -f "spring-boot:run" 2>/dev/null; pkill -f "super-biz-agent" 2>/dev/null
```

- [ ] **Step 6: 提交（如有构建配置调整）**

```bash
cd "D:\学习\SuperBizAgent" && git status
```

如果无未提交变更，跳过提交。否则：

```bash
cd "D:\学习\SuperBizAgent" && git add -A && git commit -m "chore: 构建验证通过"
```

---

### Task 9: 代码审查和推送

**Files:**
- 本次所有变更文件

- [ ] **Step 1: 查看本次变更摘要**

```bash
cd "D:\学习\SuperBizAgent" && git log --oneline -10
```

- [ ] **Step 2: 检查上下文关系和性能**

需要关注的点：
- `TextDocumentParser` 和 `PdfDocumentParser` 都是无状态的 `@Component`，线程安全 ✓
- `VectorIndexService` 构造函数中的 `parserMap` 初始化只在启动时执行一次 ✓
- PDFBox 的 `PDDocument` 在 try-with-resources 中正确关闭 ✓
- `indexDirectory()` 的 Lambda 表达式对每个文件名调用 `parserMap.keySet().stream()`，文件数量通常很小（<100），性能无影响 ✓

- [ ] **Step 3: 生成变更摘要并提交推送**

```bash
cd "D:\学习\SuperBizAgent" && git push origin feature/hybrid-recall-rrf
```

如果推送失败，重试最多 5 次：

```bash
for i in 1 2 3 4 5; do
  cd "D:\学习\SuperBizAgent" && git pull --rebase origin feature/hybrid-recall-rrf && git push origin feature/hybrid-recall-rrf && break
  echo "推送失败，第 ${i} 次重试..."
  sleep 3
done
```
