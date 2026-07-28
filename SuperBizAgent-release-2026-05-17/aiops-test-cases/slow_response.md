---
id: slow_response
severity: 警告
expected_root_causes:
  - "数据库慢查询"
  - "外部API调用超时"
  - "代码性能瓶颈"
  - "缓存失效"
  - "网络延迟"
critical_evidence:
  - "响应时间 >5s"
  - "慢查询"
  - "超时"
mock_alerts:
  - SlowResponse
mock_logs:
  - application-logs
  - database-slow-query
min_score: 12
---

# 场景：服务响应时间过长告警

## 告警背景
`order-service` P99 响应时间从 500ms 恶化到 8s，Prometheus 触发 `SlowResponse`
告警。用户反馈下单转圈等待时间过长，部分请求超时返回 504。

## 告警输入文本
```
order-service P99 响应时间飙到 8 秒了，用户抱怨下单太慢，帮我排查
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `SlowResponse` 告警详情
2. 查询慢查询日志 → 查找执行时间异常的 SQL
3. 查询应用错误日志 → 查找超时和性能相关错误
4. 分析瓶颈 → 数据库/缓存/外部依赖哪一层的延迟最高
5. 给出优化建议 → SQL 优化、加索引、增加缓存、扩容
