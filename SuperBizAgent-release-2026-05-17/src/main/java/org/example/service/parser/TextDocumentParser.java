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
