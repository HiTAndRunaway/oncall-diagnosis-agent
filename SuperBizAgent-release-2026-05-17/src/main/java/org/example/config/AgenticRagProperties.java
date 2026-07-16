package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agentic RAG 配置属性
 * <p>
 * 绑定 application.yml 中 rag.agentic.* 配置块，
 * 控制 Agentic RAG 的护栏参数和全局开关。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "rag.agentic")
public class AgenticRagProperties {

    /** 全局开关，false 时新工具不注册为 Bean，完全回退到传统 RAG */
    private boolean enabled = false;

    /** 最大检索轮次（防止 Agent 无限循环） */
    private int maxSearchRounds = 3;

    /** 最低相关性阈值（0-1），低于此值的结果视为不相关 */
    private double minRelevanceScore = 0.6;

    /** 生成答案所需的最少达标结果数 */
    private int minResultsForAnswer = 1;

    /** 降级策略：use_best（用最好的结果强制生成） */
    private String fallbackStrategy = "use_best";

    /** 整个检索阶段超时（秒） */
    private long timeoutSeconds = 60;

    /** 问题拆解时最大子问题数 */
    private int maxSubQuestions = 5;

    /** 评估结果用的轻量模型 */
    private String evaluatorModel = "qwen-turbo";

    /** 问题拆解用的模型 */
    private String decomposerModel = "qwen-turbo";
}
