package org.example.service.chunk;

import org.example.config.ChunkStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档切分策略工厂
 * 根据文件扩展名和配置选择对应的切分策略
 */
@Component
public class ChunkStrategyFactory {

    private static final Logger logger = LoggerFactory.getLogger(ChunkStrategyFactory.class);

    private final Map<String, DocumentChunkStrategy> strategyMap;
    private final ChunkStrategyProperties properties;

    public ChunkStrategyFactory(List<DocumentChunkStrategy> strategies,
                                ChunkStrategyProperties properties) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        DocumentChunkStrategy::strategyName,
                        s -> s,
                        (existing, replacement) -> {
                            logger.warn("策略名冲突: {} 已存在，后者覆盖", existing.strategyName());
                            return replacement;
                        }));
        this.properties = properties;
        logger.info("已注册 {} 个文档切分策略: {}", strategyMap.size(), strategyMap.keySet());
    }

    /**
     * 根据文件扩展名选择策略
     * 优先查 extension-overrides，未配置则使用 default-strategy
     *
     * @param fileExtension 文件扩展名（不含点号，如 "md"、"txt"），可为 null
     * @return 对应的切分策略，保证非 null
     */
    public DocumentChunkStrategy getStrategy(String fileExtension) {
        String ext = fileExtension != null ? fileExtension.toLowerCase().trim() : "";
        String strategyName = properties.getExtensionOverrides()
                .getOrDefault(ext, properties.getDefaultStrategy());

        DocumentChunkStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            logger.warn("未找到策略 '{}', 降级为 heading", strategyName);
            strategy = strategyMap.get("heading");
        }

        if (strategy == null) {
            throw new IllegalStateException(
                    "无可用的文档切分策略，请确保至少注册了 heading 策略");
        }

        return strategy;
    }

    /**
     * 获取当前默认策略名（用于日志/调试）
     */
    public String getDefaultStrategyName() {
        return properties.getDefaultStrategy();
    }
}
