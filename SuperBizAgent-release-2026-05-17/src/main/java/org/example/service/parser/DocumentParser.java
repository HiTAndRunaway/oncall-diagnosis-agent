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
