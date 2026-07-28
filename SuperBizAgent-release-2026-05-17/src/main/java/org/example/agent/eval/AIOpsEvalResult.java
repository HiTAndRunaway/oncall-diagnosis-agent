package org.example.agent.eval;

import lombok.Getter;
import lombok.Setter;

/**
 * AIOps 评估结果 DTO
 */
@Getter
@Setter
public class AIOpsEvalResult {
    /** 测试用例 ID（null 表示生产环境未知场景） */
    private String scenarioId;
    /** 根因准确度 1-5 */
    private int rootCauseAccuracy;
    /** 证据充分性 1-5 */
    private int evidenceSufficiency;
    /** 报告结构完整性 1-5 */
    private int structureCompleteness;
    /** 可操作性 1-5 */
    private int actionability;
    /** 总分（满分 20） */
    private int totalScore;
    /** 是否通过（totalScore >= minPassScore） */
    private boolean passed;
    /** Judge 的理由说明 */
    private String reasoning;
}
