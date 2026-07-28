---
id: disk_high_usage
severity: 警告
expected_root_causes:
  - "日志文件未轮转导致磁盘写满"
  - "临时文件堆积"
  - "数据库数据增长过快"
  - "备份文件未清理"
critical_evidence:
  - "磁盘使用率 >85%"
  - "磁盘空间不足"
  - "日志文件"
mock_alerts:
  - HighDiskUsage
mock_logs:
  - system-metrics
  - application-logs
min_score: 12
---

# 场景：服务器磁盘使用率过高告警

## 告警背景
应用服务器 `app-server-01` 磁盘使用率达到 89%，Prometheus 触发 `HighDiskUsage`
告警。部分定时任务写入日志失败，文件上传功能报 "No space left on device" 错误。

## 告警输入文本
```
app-server-01 磁盘快满了，89%，文件上传报错 No space left，帮忙看看
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighDiskUsage` 告警
2. 查询系统事件日志 → 查找磁盘相关错误
3. 分析磁盘空间占用 → 定位大文件/日志目录
4. 给出清理建议 → 日志轮转、清理临时文件、扩容
