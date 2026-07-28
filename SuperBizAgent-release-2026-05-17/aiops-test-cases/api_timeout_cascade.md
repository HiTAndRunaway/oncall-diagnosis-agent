---
id: api_timeout_cascade
severity: 紧急
expected_root_causes:
  - "下游服务超时导致线程池耗尽"
  - "未设置合理的超时和重试策略"
  - "雪崩效应（一个服务慢拖垮整个链路）"
  - "熔断器未正确配置"
critical_evidence:
  - "超时"
  - "线程池耗尽"
  - "级联故障"
  - "熔断"
mock_alerts:
  - HighErrorRate
  - APITimeoutCascade
mock_logs:
  - application-logs
  - database-slow-query
min_score: 12
---

# 场景：API 超时导致级联故障

## 告警背景
`recommendation-service` 调用的第三方推荐 API 突然超时（>10s），由于未设置
合理超时，Tomcat 工作线程被大量占用。随后 `order-service` 调用
`recommendation-service` 也开始超时，最终 `api-gateway` 线程池耗尽，整个
系统不可用。Prometheus 触发 `HighErrorRate` 和 `APITimeoutCascade` 告警。

## 告警输入文本
```
系统大面积超时，从推荐服务开始，现在网关也挂了，怀疑级联故障，紧急！
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighErrorRate` 和 `APITimeoutCascade`
2. 查询应用错误日志 → 查找超时异常、线程池拒绝异常
3. 追踪故障链路 → 从下游到上游排查超时根源
4. 分析根因 → 第三方 API 超时 → 线程池耗尽 → 级联超时
5. 给出紧急方案 → 对故障服务熔断降级、设置合理超时、恢复后增加熔断配置
