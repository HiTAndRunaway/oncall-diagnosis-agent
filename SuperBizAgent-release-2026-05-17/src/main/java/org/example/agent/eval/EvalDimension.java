package org.example.agent.eval;

/**
 * LLM-as-Judge 评估维度枚举
 */
public enum EvalDimension {
    /** 根因准确度 — 刚性：必须命中 expected_root_causes */
    ROOT_CAUSE_ACCURACY,
    /** 证据充分性 — 弹性：引用 critical_evidence */
    EVIDENCE_SUFFICIENCY,
    /** 报告结构完整性 — 刚性：四段式模板 */
    STRUCTURE_COMPLETENESS,
    /** 可操作性 — 弹性：步骤具体可执行 */
    ACTIONABILITY
}
