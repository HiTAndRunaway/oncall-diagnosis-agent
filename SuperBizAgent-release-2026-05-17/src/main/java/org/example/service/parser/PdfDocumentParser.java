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
