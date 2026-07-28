---
id: service_unavailable
severity: 紧急
expected_root_causes:
  - "应用崩溃或无法启动"
  - "数据库连接失败"
  - "依赖服务故障"
  - "配置错误"
  - "资源耗尽"
critical_evidence:
  - "健康检查失败"
  - "HTTP 500"
  - "服务不可用"
  - "建议回滚"
mock_alerts:
  - ServiceUnavailable
mock_logs:
  - application-logs
  - system-events
min_score: 12
---

# 场景：核心服务不可用紧急告警

## 告警背景
`payment-service` 在 16:00 突然所有健康检查失败，Nginx 返回 502 Bad Gateway。
用户无法完成支付，影响范围广泛。Prometheus 触发 `ServiceUnavailable` 紧急告警。
距离上次部署仅 30 分钟，怀疑与新版本有关。

## 告警输入文本
```
payment-service 挂了，所有请求返回 502，用户无法支付，紧急！30分钟前刚发过版
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `ServiceUnavailable` 告警级别和影响范围
2. 查询应用日志 → 查找启动失败、数据库连接错误、配置加载异常
3. 查询系统事件日志 → 查找 OOM Kill、crash 记录
4. 快速定位 → 根因是部署问题还是依赖故障
5. 给出紧急处理方案 → 优先回滚到上一版本，再深入排查
