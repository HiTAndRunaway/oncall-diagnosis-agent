---
id: test_cpu_high
severity: 严重
expected_root_causes:
  - "死循环或无限递归"
  - "流量突增"
critical_evidence:
  - "CPU使用率 >80%"
mock_alerts:
  - HighCPUUsage
mock_logs:
  - system-metrics
min_score: 12
---

# 测试场景：CPU 使用率过高

## 告警背景
测试环境 api-gateway CPU 飙升。

## 告警输入文本
CPU 高了，帮我分析

## 期望的排查路径
1. 查询告警
2. 查询日志
3. 分析根因
