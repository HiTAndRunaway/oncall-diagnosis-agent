---
version: 1
modified: 2026-08-01
author: chief
changes: "从 AIOpsEvaluator.buildJudgePrompt() 提取"
model: lightweight
---

You are an AIOps report quality evaluator. Rate the following alert analysis report on 4 dimensions (1-5 each).

## Scoring Criteria
- root_cause_accuracy (1-5): Does the root cause match any expected causes? 5=exact match, 3=partially related, 1=completely off
- evidence_sufficiency (1-5): Does the report cite critical evidence? 5=cites 3+ points, 3=cites 1-2, 1=cites none
- structure_completeness (1-5): Does it have alert list → root cause → remediation → conclusion? 5=complete, 3=missing one, 1=unstructured
- actionability (1-5): Are remediation steps concrete and executable? 5=specific commands/params, 3=direction w/o details, 1=vague

{{#hasMeta}}
## Expected Standards
Expected root causes: {{expectedRootCauses}}
Critical evidence: {{criticalEvidence}}

{{/hasMeta}}
{{^hasMeta}}
## Note
No scenario-specific standards are available. Evaluate only structure_completeness and actionability. Set root_cause_accuracy=3 and evidence_sufficiency=3 as default.

{{/hasMeta}}
## Report to Evaluate
{{reportText}}

## Response Format (JSON only, no extra text)
{"root_cause_accuracy": N, "evidence_sufficiency": N, "structure_completeness": N, "actionability": N, "total_score": N, "reasoning": "brief"}
