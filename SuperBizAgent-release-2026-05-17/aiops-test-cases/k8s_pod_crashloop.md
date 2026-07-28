---
id: k8s_pod_crashloop
severity: 紧急
expected_root_causes:
  - "容器启动失败（配置错误或依赖不可用）"
  - "OOMKilled（内存超限被 K8s 杀掉）"
  - "健康检查探针配置错误"
  - "镜像拉取失败"
critical_evidence:
  - "CrashLoopBackOff"
  - "OOMKilled"
  - "ImagePullBackOff"
  - "重启次数"
mock_alerts:
  - K8sPodCrashLoop
mock_logs:
  - application-logs
  - system-events
min_score: 12
---

# 场景：Kubernetes Pod CrashLoopBackOff

## 告警背景
`notification-service` 部署在 K8s 集群中，在滚动更新后 Pod 反复重启，
状态变为 CrashLoopBackOff，重启次数已超过 20 次。Prometheus 触发 `K8sPodCrashLoop` 告警。
通知服务不可用，用户收不到订单确认消息。

## 告警输入文本
```
K8s notification-service Pod CrashLoopBackOff，已重启 20 多次，通知发不出去，紧急！
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `K8sPodCrashLoop` 告警
2. 查询系统事件日志 → 查看 Pod Events（OOMKilled/ImagePullBackOff 等）
3. 查询应用日志 → 查看启动日志中的错误信息
4. 快速定位 → OOM/配置错误/镜像问题/健康检查失败
5. 给出处理方案 → 回滚部署、调整资源限制、修复配置
