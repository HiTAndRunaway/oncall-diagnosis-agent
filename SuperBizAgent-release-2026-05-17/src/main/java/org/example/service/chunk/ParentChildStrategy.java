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
        int parentCount = 0;

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
            parentCount++;

            if (parentEnd >= content.length()) {
                break;
            }
            pos += parentStep;
        }

        logger.info("parent-child 策略切分完成: {} -> {} 个 Parent, {} 个 Child (parentSize={}, childSize={})",
                filePath, parentCount, allChildren.size(), parentSize, childSize);
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
