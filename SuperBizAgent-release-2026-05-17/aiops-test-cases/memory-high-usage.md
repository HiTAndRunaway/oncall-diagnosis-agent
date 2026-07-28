---
id: memory-high-usage
severity: critical
expected_root_causes:
  - Memory leak in order service causing gradual heap exhaustion
  - Excessive object retention in order processing cache
  - Insufficient JVM heap configuration for workload
critical_evidence:
  - Memory usage 91% on 4GB JVM heap
  - Frequent Full GC events (15 in 10 minutes)
  - OutOfMemoryError occurrences
  - Pod restarts due to OOMKill
mock_alerts:
  - HighMemoryUsage
mock_logs:
  - system-metrics
  - application-logs
  - system-events
min_score: 12
---

# Memory High Usage Test Case

## Scenario Description
The order-service is experiencing critically high memory usage at 91% of its 4GB JVM heap. Full GC events are frequent (15 in the last 10 minutes), and the service has experienced OOMKill restarts. Survivor space and old generation are heavily saturated.

## Expected Behavior
The AIOps agent should:
1. Query active Prometheus alerts to identify the HighMemoryUsage alert
2. Query system-metrics logs for memory usage patterns
3. Query system-events for OOMKill / Pod restart events
4. Query application-logs for OutOfMemoryError stack traces
5. Identify the root cause (memory leak, cache retention, or heap misconfiguration)
6. Propose actionable remediation steps

## Mock Data Reference
- Alerts: HighMemoryUsage alert for order-service
- Logs: system-metrics showing memory trend, application-logs with OOM errors, system-events with Pod restarts
