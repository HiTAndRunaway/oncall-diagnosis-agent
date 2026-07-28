package org.example.agent.eval;

import lombok.Getter;
import lombok.Setter;

/**
 * AIOps evaluation result DTO.
 * <p>
 * Holds per-dimension scores (1-5) for an AIOps report evaluation,
 * plus a total score (max 20), reasoning text, and pass/fail flag.
 */
@Getter
@Setter
public class AIOpsEvalResult {

    /** Test case scenario identifier (null for generic evaluation). */
    private String scenarioId;

    /** Root cause accuracy score (1-5). */
    private int rootCauseAccuracy;

    /** Evidence sufficiency score (1-5). */
    private int evidenceSufficiency;

    /** Structure completeness score (1-5). */
    private int structureCompleteness;

    /** Actionability score (1-5). */
    private int actionability;

    /** Total score (sum of all dimensions, max 20). */
    private int totalScore;

    /** Brief reasoning from the LLM judge. */
    private String reasoning;

    /** Whether the report passed (totalScore >= minPassScore). */
    private boolean passed;

}
