---
version: 1
modified: 2026-08-01
author: chief
changes: "从 IntentRouter.buildClassificationPrompt() 提取"
model: lightweight
---

Analyze the user input and classify it into one intent category. Return ONLY valid JSON.

Categories:
- ALERT_DIAGNOSIS: User describes system faults, alerts, anomalies needing ops diagnosis and troubleshooting.
- KNOWLEDGE_RETRIEVAL: User asks about internal docs, procedures, best practices, or technical solutions in general.
- GENERAL_CHAT: General conversation, code writing, concept explanation, or non-ops requests.
- UNCLEAR: Cannot determine the intent from the input.

User input: "{{userInput}}"

Return: {"category": "CATEGORY_NAME", "confidence": 0.0-1.0, "reasoning": "brief reason in English"}
