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
                List<DocumentChunk> subChunks = splitLongParagraph(
                        paragraph, maxSize, overlap,
                        currentStartIndex + currentChunk.length(),
                        chunkIndex);
                chunks.addAll(subChunks);
                chunkIndex += subChunks.size();
                // 重置 currentChunk 为新段落开头
                DocumentChunk lastChunk = subChunks.get(subChunks.size() - 1);
                String overlapText = getOverlapAlignedToSentence(lastChunk.getContent(), overlap);
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
