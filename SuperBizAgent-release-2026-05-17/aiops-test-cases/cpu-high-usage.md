---
id: cpu-high-usage
severity: critical
expected_root_causes:
  - CPU-intensive background job causing thread pool exhaustion
  - Infinite loop in payment processing service
  - Sudden traffic spike overwhelming CPU capacity
critical_evidence:
  - CPU usage 92%+ for 25 minutes
  - Java process consuming 4-core CPU
  - Thread count 245 (elevated)
  - No recent deployment or config changes
mock_alerts:
  - HighCPUUsage
mock_logs:
  - system-metrics
  - application-logs
min_score: 12
---

# CPU High Usage Test Case

## Scenario Description
The payment-service has been experiencing sustained CPU usage above 80% for 25+ minutes, peaking at 92%. The service is deployed on a 4-core pod in the production namespace. No recent deployments or configuration changes have been made.

## Expected Behavior
The AIOps agent should:
1. Query active Prometheus alerts to identify the HighCPUUsage alert
2. Query system-metrics logs to understand CPU pattern
3. Identify the root cause (background job, infinite loop, or traffic spike)
4. Propose actionable remediation steps

## Mock Data Reference
- Alerts: HighCPUUsage alert for payment-service
- Logs: system-metrics showing CPU pattern, application-logs showing related errors
