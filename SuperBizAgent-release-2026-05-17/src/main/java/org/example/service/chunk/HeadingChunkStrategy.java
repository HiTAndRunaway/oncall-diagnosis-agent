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
