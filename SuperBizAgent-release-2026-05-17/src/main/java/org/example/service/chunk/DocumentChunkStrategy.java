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
