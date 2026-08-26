package org.example.service.multiquery;

/**
 * 多角度查询变体
 *
 * @param index     变体序号（1..max-variants，LLM 返回顺序）
 * @param query     角度改写后的查询文本（可独立检索）
 * @param angle     角度类型（KEYWORD / SCENE / SUB_QUESTION / CAUSE_STEP / COMPARE）
 * @param rationale 该角度的生成理由（用于日志/可观测性）
 */
public record QueryVariant(int index, String query, String angle, String rationale) {
}
