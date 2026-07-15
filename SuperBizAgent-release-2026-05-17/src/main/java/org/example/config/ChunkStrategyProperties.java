package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档切分策略配置属性
 * 读取 application.yml 中 document.chunk.strategy.* 配置块
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "document.chunk.strategy")
public class ChunkStrategyProperties {

    /** 全局默认策略名，默认 heading */
    private String defaultStrategy = "heading";

    /** 扩展名 → 策略名覆盖映射（不含点号，小写，如 "txt" → "fixed-size"） */
    private Map<String, String> extensionOverrides = new HashMap<>();

    /** 各策略的独立配置参数 */
    private Map<String, StrategyConfig> strategies = new HashMap<>();

    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    public void setExtensionOverrides(Map<String, String> extensionOverrides) {
        this.extensionOverrides = extensionOverrides;
    }

    public void setStrategies(Map<String, StrategyConfig> strategies) {
        this.strategies = strategies;
    }

    @Getter
    @Setter
    public static class StrategyConfig {
        private int maxSize = 800;
        private int overlap = 100;
        /** parent-child 专用：子块最大字符数 */
        private Integer childSize;
        /** parent-child 专用：父块最大字符数 */
        private Integer parentSize;
    }
}
