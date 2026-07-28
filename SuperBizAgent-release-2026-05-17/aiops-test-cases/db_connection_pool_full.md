---
id: db_connection_pool_full
severity: 严重
expected_root_causes:
  - "数据库连接池耗尽"
  - "慢SQL导致连接堆积"
  - "连接池配置过小"
  - "连接泄漏未释放"
critical_evidence:
  - "连接池耗尽"
  - "Cannot get JDBC Connection"
  - "Too many connections"
mock_alerts:
  - HighDatabaseConnectionUsage
mock_logs:
  - database-slow-query
  - database-error-logs
min_score: 12
---

# 场景：数据库连接池耗尽告警

## 告警背景
生产环境核心服务 `order-service` 在 14:30 突然报出大量 500 错误。
Prometheus 触发 `HighDatabaseConnectionUsage` 告警（数据库连接数超过 90%，当前 185/200）。
用户反馈下单失败，影响范围正在扩大。

## 告警输入文本
```
order-service 数据库连接池耗尽，大量 500 错误，需要紧急排查
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighDatabaseConnectionUsage` 告警详情
2. 查询数据库慢查询日志 → 发现某 SQL 执行超过 30s 导致连接堆积
3. 查询应用错误日志 → 发现 "Cannot get JDBC Connection" 错误
4. 分析根因 → 定位到慢 SQL 导致连接堆积
5. 给出修复建议 → 优化 SQL、增加连接池大小、启用查询超时、kill 慢查询
