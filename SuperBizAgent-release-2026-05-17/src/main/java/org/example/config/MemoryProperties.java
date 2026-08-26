package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Mem0 风格长期记忆配置属性
 * 通过 {@code @ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")} 控制所有记忆功能
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    /** 全局开关，false 时所有记忆功能不注册 */
    private boolean enabled = true;

    private Extraction extraction = new Extraction();
    private Search search = new Search();
    private Decay decay = new Decay();
    private Ttl ttl = new Ttl();
    private SystemPrompt systemPrompt = new SystemPrompt();

    // ===== 嵌套配置类 =====

    /**
     * 记忆提取配置
     */
    @Getter
    @Setter
    public static class Extraction {
        /** 会话新增消息对超过此数触发记忆提取 */
        private int triggerMessageCount = 6;
        /** 提取 + 冲突判断用的轻量 LLM */
        private String model = "qwen-turbo";
        /** 一次提取最多分析的对话条数 */
        private int maxBatchMessages = 50;
    }

    /**
     * 记忆检索配置
     */
    @Getter
    @Setter
    public static class Search {
        /** recallMemory 默认返回数 */
        private int topK = 5;
        /** 冲突检测时向量相似度最低阈值 */
        private double scoreThreshold = 0.6;
    }

    /**
     * 记忆衰减配置
     */
    @Getter
    @Setter
    public static class Decay {
        /** 衰减开关 */
        private boolean enabled = true;
        /** 定时任务 cron 表达式，默认每天凌晨 3 点 */
        private String cron = "0 3 * * *";
        /** 每次衰减的置信度减少量 */
        private double decayFactor = 0.1;
        /** 低于此置信度自动删除 */
        private double minConfidence = 0.3;
        /** 无访问触发衰减的阈值（小时），默认 168（7天） */
        private int noAccessThresholdHours = 168;
    }

    /**
     * 记忆 TTL 配置
     */
    @Getter
    @Setter
    public static class Ttl {
        /** FACT 类型 TTL（小时），0 表示永不过期 */
        private int factHours = 0;
        /** PROFILE 类型 TTL（小时），默认 2160（90天） */
        private int profileHours = 2160;
        /** PREFERENCE 类型 TTL（小时），默认 720（30天） */
        private int preferenceHours = 720;
    }

    /**
     * System Prompt 注入配置
     */
    @Getter
    @Setter
    public static class SystemPrompt {
        /** 是否注入用户画像到 System Prompt */
        private boolean injectProfile = true;
        /** 是否注入行为偏好到 System Prompt */
        private boolean injectPreferences = true;
        /** 注入内容最大字符数 */
        private int maxLength = 500;
    }
}
