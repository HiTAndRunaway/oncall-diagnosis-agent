---
id: cpu_high_usage
severity: 严重
expected_root_causes:
  - "死循环或无限递归"
  - "流量突增"
  - "定时任务重叠执行"
  - "数据库慢查询导致CPU堆积"
critical_evidence:
  - "CPU使用率 >80%"
  - "建议重启"
  - "扩容"
mock_alerts:
  - HighCPUUsage
mock_logs:
  - system-metrics
  - application-logs
min_score: 12
---

# 场景：生产环境 CPU 使用率过高告警

## 告警背景
生产环境核心服务 `api-gateway` 在 14:30 触发 Prometheus `HighCPUUsage` 告警，
CPU 使用率持续 5 分钟超过 80%，峰值达到 95%。`order-service` 和 `payment-service` 
两个下游服务也出现响应延迟增加。

## 告警输入文本
```
生产环境 api-gateway CPU 使用率飙升到 95%，响应变慢，帮我分析一下原因
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighCPUUsage` 告警详情（持续时间、峰值）
2. 查询系统指标日志（system-metrics） → 找到 CPU 占用 Top 进程
3. 查询应用错误日志 → 查找是否有死循环/异常堆栈
4. 分析根因 → 定位到具体原因（死循环/流量突增/定时任务等）
5. 给出修复建议 → 重启、扩容、限流、代码修复等具体措施
