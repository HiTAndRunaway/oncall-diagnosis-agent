package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话 Redis 存储配置属性
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "session.redis")
public class SessionRedisProperties {

    /** 会话过期时间（小时），0=永不过期，默认24 */
    private int ttlHours = 24;

    /** 摘要配置 */
    private Summary summary = new Summary();

    @Getter
    @Setter
    public static class Summary {
        /** 是否启用摘要层查询，false=跳过摘要直接查详情 */
        private boolean enabled = true;

        /** 消息对数超过此阈值触发摘要生成 */
        private int triggerThreshold = 10;

        /** 生成摘要用的 LLM 模型 */
        private String model = "qwen3-max";

        /** 摘要最大字符数 */
        private int maxSummaryLength = 500;
    }
}
