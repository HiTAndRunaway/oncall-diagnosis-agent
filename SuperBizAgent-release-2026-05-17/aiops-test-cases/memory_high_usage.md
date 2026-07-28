---
id: memory_high_usage
severity: 严重
expected_root_causes:
  - "内存泄漏"
  - "JVM堆内存配置过小"
  - "缓存配置不当导致内存膨胀"
  - "大对象频繁创建"
critical_evidence:
  - "内存使用率 >85%"
  - "GC频繁"
  - "OutOfMemoryError"
mock_alerts:
  - HighMemoryUsage
mock_logs:
  - system-metrics
  - application-logs
min_score: 12
---

# 场景：生产环境内存使用率过高告警

## 告警背景
`user-service` 在运行 48 小时后内存使用率持续攀升至 92%，Full GC 频繁触发
（每 30 秒一次），接口响应时间从 200ms 恶化到 5s。Prometheus 触发
`HighMemoryUsage` 告警。

## 告警输入文本
```
user-service 内存使用率 92%，Full GC 频繁，接口响应超时，请帮我排查
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighMemoryUsage` 告警详情
2. 查询系统指标日志 → 查看 JVM 堆内存使用趋势
3. 查询应用错误日志 → 查找 OOM 或内存相关错误
4. 分析根因 → 判断是内存泄漏还是配置问题
5. 给出修复建议 → 调整 JVM 参数、排查内存泄漏点、重启服务
