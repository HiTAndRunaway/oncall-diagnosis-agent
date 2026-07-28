package org.example.agent.router;

import lombok.Getter;
import lombok.Setter;

/**
 * 意图分类结果 DTO
 */
@Getter
@Setter
public class IntentResult {
    /** 分类类别 */
    private IntentCategory category;
    /** 置信度 0.0 - 1.0 */
    private double confidence;
    /** 分类理由简述 */
    private String reasoning;

    /**
     * 快捷构造：GENERAL_CHAT（降级用）
     */
    public static IntentResult fallback(String reasoning) {
        IntentResult result = new IntentResult();
        result.setCategory(IntentCategory.GENERAL_CHAT);
        result.setConfidence(0.0);
        result.setReasoning(reasoning);
        return result;
    }

    /**
     * 快捷构造：指定类别
     */
    public static IntentResult of(IntentCategory category, double confidence, String reasoning) {
        IntentResult result = new IntentResult();
        result.setCategory(category);
        result.setConfidence(confidence);
        result.setReasoning(reasoning);
        return result;
    }
}
