package org.example.agent.eval;

/**
 * AIOps report evaluation dimensions.
 * <p>
 * Four dimensions are used by the LLM-as-Judge evaluator:
 * <ul>
 *   <li>ROOT_CAUSE_ACCURACY — rigid: must match expected_root_causes</li>
 *   <li>EVIDENCE_SUFFICIENCY — elastic: cites critical_evidence</li>
 *   <li>STRUCTURE_COMPLETENESS — rigid: report has all 4 sections</li>
 *   <li>ACTIONABILITY — elastic: steps are specific</li>
 * </ul>
 */
public enum EvalDimension {

    ROOT_CAUSE_ACCURACY,
    EVIDENCE_SUFFICIENCY,
    STRUCTURE_COMPLETENESS,
    ACTIONABILITY

}
