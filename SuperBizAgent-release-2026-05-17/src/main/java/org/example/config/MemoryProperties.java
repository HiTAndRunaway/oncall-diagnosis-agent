package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Mem0 风格长期记忆配置属性
 * 通过 {@code @ConditionalOnProperty(prefix = "memory", name = "enabled", havingValue = "true")} 控制所有记忆功能
 */
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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Extraction getExtraction() { return extraction; }
    public void setExtraction(Extraction extraction) { this.extraction = extraction; }

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }

    public Decay getDecay() { return decay; }
    public void setDecay(Decay decay) { this.decay = decay; }

    public Ttl getTtl() { return ttl; }
    public void setTtl(Ttl ttl) { this.ttl = ttl; }

    public SystemPrompt getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(SystemPrompt systemPrompt) { this.systemPrompt = systemPrompt; }

    // ===== 嵌套配置类 =====

    public static class Extraction {
        /** 会话新增消息对超过此数触发记忆提取 */
        private int triggerMessageCount = 6;
        /** 提取 + 冲突判断用的轻量 LLM */
        private String model = "qwen-turbo";
        /** 一次提取最多分析的对话条数 */
        private int maxBatchMessages = 50;

        public int getTriggerMessageCount() { return triggerMessageCount; }
        public void setTriggerMessageCount(int triggerMessageCount) { this.triggerMessageCount = triggerMessageCount; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxBatchMessages() { return maxBatchMessages; }
        public void setMaxBatchMessages(int maxBatchMessages) { this.maxBatchMessages = maxBatchMessages; }
    }

    public static class Search {
        /** recallMemory 默认返回数 */
        private int topK = 5;
        /** 冲突检测时向量相似度最低阈值 */
        private double scoreThreshold = 0.6;

        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getScoreThreshold() { return scoreThreshold; }
        public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
    }

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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public double getDecayFactor() { return decayFactor; }
        public void setDecayFactor(double decayFactor) { this.decayFactor = decayFactor; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
        public int getNoAccessThresholdHours() { return noAccessThresholdHours; }
        public void setNoAccessThresholdHours(int noAccessThresholdHours) { this.noAccessThresholdHours = noAccessThresholdHours; }
    }

    public static class Ttl {
        /** FACT 类型 TTL（小时），0 表示永不过期 */
        private int factHours = 0;
        /** PROFILE 类型 TTL（小时），默认 2160（90天） */
        private int profileHours = 2160;
        /** PREFERENCE 类型 TTL（小时），默认 720（30天） */
        private int preferenceHours = 720;

        public int getFactHours() { return factHours; }
        public void setFactHours(int factHours) { this.factHours = factHours; }
        public int getProfileHours() { return profileHours; }
        public void setProfileHours(int profileHours) { this.profileHours = profileHours; }
        public int getPreferenceHours() { return preferenceHours; }
        public void setPreferenceHours(int preferenceHours) { this.preferenceHours = preferenceHours; }
    }

    public static class SystemPrompt {
        /** 是否注入用户画像到 System Prompt */
        private boolean injectProfile = true;
        /** 是否注入行为偏好到 System Prompt */
        private boolean injectPreferences = true;
        /** 注入内容最大字符数 */
        private int maxLength = 500;

        public boolean isInjectProfile() { return injectProfile; }
        public void setInjectProfile(boolean injectProfile) { this.injectProfile = injectProfile; }
        public boolean isInjectPreferences() { return injectPreferences; }
        public void setInjectPreferences(boolean injectPreferences) { this.injectPreferences = injectPreferences; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }
}
