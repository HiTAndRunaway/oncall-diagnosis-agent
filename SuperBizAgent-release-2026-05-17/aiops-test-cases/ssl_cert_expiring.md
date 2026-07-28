---
id: ssl_cert_expiring
severity: 警告
expected_root_causes:
  - "SSL证书即将到期未续期"
  - "证书自动续期脚本失败"
  - "CA证书链变更"
critical_evidence:
  - "证书即将过期"
  - "SSL/TLS"
  - "到期时间"
mock_alerts:
  - SSLCertExpiring
mock_logs:
  - application-logs
  - system-events
min_score: 12
---

# 场景：SSL 证书即将过期告警

## 告警背景
生产环境 `api.example.com` 域名的 SSL 证书将在 7 天后过期。Prometheus
证书监控触发 `SSLCertExpiring` 告警。如果证书过期，所有 HTTPS 请求将失败，
用户将无法访问服务。

## 告警输入文本
```
api.example.com 的 SSL 证书还有 7 天就过期了，需要处理续期
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `SSLCertExpiring` 告警
2. 查询系统事件日志 → 查找证书相关错误或自动续期失败记录
3. 评估影响范围 → 确认哪些域名/服务受影响
4. 给出处理方案 → 手动续期或触发自动续期脚本、更新证书、验证新证书
