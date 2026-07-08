# 文件上传支持 TXT 和 PDF 格式 — 设计方案

**日期:** 2026-07-08
**分支:** feature/hybrid-recall-rrf
**状态:** 已确认

---

## 1. 背景与目标

当前文件上传和文档分片仅对 Markdown（`.md`）有完善的智能切分支持。`.txt` 虽已在配置和后端白名单中允许，但前端未放开，且无格式特定的处理策略。`.pdf` 完全不被支持。

**目标：**
- `.txt` 文件完整打通上传 → 切分 → 向量化全链路
- `.pdf` 文件支持文本型 PDF（文档型），扫描件检测并跳过（OCR 留待后续迭代）
- 架构上支持未来平滑扩展新格式或迁移到统一解析方案（如 Apache Tika）

---

## 2. 当前状态

| 模块 | 状态 |
|------|------|
| `application.yml` | `allowed-extensions: txt,md` — txt 已配置 |
| `FileUploadController` | 扩展名校验通过，txt/md 均可上传 |
| `DocumentChunkService` | 仅实现 Markdown 标题正则切分（`#{1,6}`），无标题时回退为段落+字数切分 |
| `VectorIndexService` | `Files.readString()` 读取 — txt/md 有效，PDF 无效 |
| `VectorIndexService.indexDirectory()` | 硬编码过滤 `.txt` 和 `.md` |
| 前端 `index.html` | `accept=".txt,.md,.markdown"` |
| 前端 `app.js` | 校验 `['.txt', '.md', '.markdown']` |
| PDF 依赖 | 零——pom.xml 无任何 PDF 解析库 |

**结论：** txt 后端链路基本可用，前端和目录扫描需补齐；PDF 需从零建设解析能力。

---

## 3. 设计决策

### 3.1 架构方案：策略模式（Parser 接口 + 按格式实现）

**选择理由：**
- 与现有 `DocumentChunkService`、`VectorEmbeddingService` 的单一职责风格一致
- 每种格式独立封装，可单独测试
- 新增格式只需加一个实现类，不改调用方
- 未来迁移到 Apache Tika 只需新增一个 `TikaDocumentParser` 替换实现，`VectorIndexService` 零改动

**接口契约：**

```java
public interface DocumentParser {
    List<String> supportedExtensions();      // 如 ["pdf"] / ["txt", "md", "markdown"]
    String parse(Path filePath) throws DocumentParseException;
}
```

### 3.2 Chunk 策略：复用现有段落切分

PDF 提取文本后直接交给 `DocumentChunkService.chunkDocument()`。PDF 文本没有 Markdown 标题，自动走段落切分分支——即"软边界固定大小"策略（在 800 字符附近的段落边界截断，100 字符重叠）。与用户需求"按固定大小切分"一致，且语义完整性更好。

### 3.3 OCR：本次不做

扫描件检测策略：PDF 提取文本后若 ≤ 50 字符，判定为疑似扫描件，记录 warn 日志并返回空字符串（chunk 列表为空，跳过索引）。后续迭代时在 `PdfDocumentParser` 中集成 OCR 即可，不影响其他模块。

---

## 4. 文件变更清单

### 新增文件

```
src/main/java/org/example/service/parser/
├── DocumentParser.java           # 解析器接口
├── TextDocumentParser.java       # txt / md / markdown
├── PdfDocumentParser.java        # pdf (PDFBox 文本提取)
└── DocumentParseException.java   # 解析异常
```

### 修改文件

```
src/main/java/org/example/service/VectorIndexService.java   # 改用 Parser 读取文件
src/main/java/org/example/config/FileUploadConfig.java      # (无代码改动，配置驱动)
src/main/resources/application.yml                          # allowed-extensions 加 pdf
src/main/resources/static/index.html                        # accept 加 .pdf
src/main/resources/static/app.js                            # 校验加 .pdf，文案更新
pom.xml                                                      # 添加 pdfbox 依赖
```

---

## 5. 详细设计

### 5.1 DocumentParser 接口

```java
package org.example.service.parser;

import java.nio.file.Path;
import java.util.List;

public interface DocumentParser {
    List<String> supportedExtensions();
    String parse(Path filePath) throws DocumentParseException;
}
```

### 5.2 TextDocumentParser

- `supportedExtensions()` → `["txt", "md", "markdown"]`
- `parse()` → `Files.readString(path)` （UTF-8，与现有行为一致）
- 文件为空 → 返回空字符串（`DocumentChunkService` 已有处理）

### 5.3 PdfDocumentParser

- `supportedExtensions()` → `["pdf"]`
- `parse()` 流程：
  1. `PDDocument.load(path.toFile())` 加载 PDF
  2. 加密检测：捕获 `InvalidPasswordException` → 抛 `DocumentParseException("PDF 已加密，无法解析")`
  3. `PDFTextStripper` 逐页提取文本
  4. 扫描件检测：提取后 `text.trim().length() <= 50` → warn 日志 "可能为扫描件" → 返回 `""`
  5. 返回提取的纯文本
- **注意：** 中文 PDF 需要 PDFBox 能处理，通常文档型中文 PDF 可以正常提取

### 5.4 DocumentParseException

```java
package org.example.service.parser;

public class DocumentParseException extends RuntimeException {
    public DocumentParseException(String message) { super(message); }
    public DocumentParseException(String message, Throwable cause) { super(message, cause); }
}
```

继承 `RuntimeException`：parse 失败是文件本身的问题（加密/损坏），不需要调用方显式 catch，由上层统一异常处理兜底。

### 5.5 VectorIndexService 改动

**构造函数注入所有 Parser 实现：**

```java
public VectorIndexService(List<DocumentParser> parsers) {
    this.parserMap = new HashMap<>();
    for (DocumentParser parser : parsers) {
        for (String ext : parser.supportedExtensions()) {
            parserMap.put(ext.toLowerCase(), parser);
        }
    }
}
```

**`indexSingleFile()` 改动：**

```java
// 原: String content = Files.readString(path);
// 改:
String extension = getFileExtension(filePath);
DocumentParser parser = parserMap.get(extension.toLowerCase());
if (parser == null) {
    throw new DocumentParseException("不支持的文件格式: " + extension);
}
String content = parser.parse(path);
```

**`indexDirectory()` 改动：**

```java
// 原: name.endsWith(".txt") || name.endsWith(".md")
// 改: parserMap.keySet().stream().anyMatch(ext -> name.endsWith("." + ext))
```

**其他方法不变**（`deleteExistingData`、`buildMetadata`、`insertToMilvus` 全都不需要改）。

### 5.6 前端改动

| 文件 | 位置 | 改动 |
|------|------|------|
| `index.html:115` | `<input accept>` | 加上 `.pdf` → `accept=".txt,.md,.markdown,.pdf"` |
| `app.js:1041` | `allowedExtensions` 数组 | 加上 `'.pdf'` |
| `app.js:1030,1049` | 错误提示文案 | 改为 "支持 TXT / Markdown / PDF 格式" |

### 5.7 配置文件

```yaml
file:
  upload:
    allowed-extensions: txt,md,pdf
```

---

## 6. 异常处理矩阵

| 场景 | 检测方式 | 行为 |
|------|----------|------|
| PDF 已加密 | `InvalidPasswordException` | 抛 `DocumentParseException`，前端收到 500 + 错误消息 |
| PDF 疑似扫描件 | 提取文本 ≤ 50 字符 | warn 日志，返回空字符串，chunk 为空，跳过索引 |
| 文件编码异常 | `Files.readString()` 抛异常 | 现有 catch 兜底，500 + 错误消息 |
| 文件不是有效 PDF | `PDFBox` 抛 `IOException` | 包装为 `DocumentParseException` |
| 未知扩展名 | `parserMap.get()` 返回 null | 抛 `DocumentParseException("不支持的文件格式")` |

---

## 7. Maven 依赖

```xml
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.3</version>
</dependency>
```

PDFBox 3.0.3 约 3MB，纯 Java 无原生依赖，Apache License 2.0。

---

## 8. 未来迭代路径

| 场景 | 改动范围 |
|------|----------|
| 新增格式（如 `.docx`） | 新增一个 `DocxDocumentParser` + pom 加依赖 |
| 集成 OCR（扫描件 PDF） | 仅在 `PdfDocumentParser.parse()` 中添加 OCR 分支 |
| 迁移到 Apache Tika | 新增 `TikaDocumentParser`，删除旧实现类，`VectorIndexService` 零改动 |
| 按格式配置不同 chunk 策略 | `DocumentParser` 接口扩展 `chunkStrategy()` 方法 |

---

## 9. 测试策略

| 层级 | 测试内容 |
|------|----------|
| 单元测试 | `TextDocumentParser` 各扩展名正确解析 |
| 单元测试 | `PdfDocumentParser` 正常 PDF / 加密 PDF / 扫描件 / 空文件 |
| 单元测试 | `VectorIndexService.selectParser()` 正确分发 |
| 集成测试 | `POST /api/upload` 上传 `.txt` / `.pdf` → 验证 Milvus 插入 |
| 手动验证 | 前端文件选择器显示 PDF 选项，上传成功提示 |
