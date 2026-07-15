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
