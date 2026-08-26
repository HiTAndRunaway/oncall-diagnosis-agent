package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多角度查询召回配置属性
 * <p>
 * 读取 application.yml 中 {@code rag.multi-query.*} 配置块。
 * 多角度查询为可选召回路（Road C）：LLM 将原始问题按多个角度改写为
 * {@code max-variants} 个查询变体（不含原始查询），每个变体独立检索，
 * 变体间 RRF 融合后作为一路与 Dense/BM25 两路做三路 RRF 融合。
 * <p>
 * 全有或全无（all-or-nothing）：开启后任何环节失败即整路放弃，降级为两路召回。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "rag.multi-query")
public class MultiQueryProperties {

    /** 可选能力总开关；false = 不启用多角度路，维持两路召回 */
    private boolean enabled = false;

    /** 生成的角度变体数量（不含原始查询），默认 5，可配置 */
    private int maxVariants = 5;

    /** 变体生成用的轻量 LLM 模型 */
    private String model = "qwen-turbo";

    /** 生成多样性温度 */
    private double temperature = 0.7;

    /** 单次生成的 max_tokens */
    private int maxTokens = 500;

    /** LLM 生成整体超时（秒），超时即整路放弃、降级两路 */
    private int timeoutSeconds = 15;

    /** 每个变体的检索召回数 */
    private int variantRecallCount = 10;

    /** 多角度路内部融合后保留条数（默认对齐 rag.recall-count） */
    private int variantTopK = 30;

    /** 变体检索方式：dense | hybrid（默认 dense） */
    private String variantSearchMode = "dense";

    /** 变体间 RRF 平滑常数 */
    private int variantRrfK = 60;

    /** 多角度路在三路 RRF 中的权重 */
    private double weight = 1.0;

    /** 角度模板覆盖（留空用内置五类角度模板） */
    private String anglePrompt = "";

    /** 缓存配置 */
    private Cache cache = new Cache();

    /**
     * 缓存配置
     */
    @Getter
    @Setter
    public static class Cache {
        /** 是否启用 Redis 缓存 */
        private boolean enabled = true;
        /** 缓存过期时间（小时） */
        private int ttlHours = 1;
    }
}
