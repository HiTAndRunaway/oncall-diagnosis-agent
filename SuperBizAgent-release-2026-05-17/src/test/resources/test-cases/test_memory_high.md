---
id: test_memory_high
severity: 严重
expected_root_causes:
  - "内存泄漏"
  - "JVM堆内存配置过小"
critical_evidence:
  - "内存使用率 >85%"
mock_alerts:
  - HighMemoryUsage
mock_logs:
  - system-metrics
min_score: 12
---

# 测试场景：内存使用率过高

## 告警背景
user-service 内存持续攀升。

## 告警输入文本
内存快满了，帮忙排查

## 期望的排查路径
1. 查询告警
2. 查询日志
3. 分析根因
