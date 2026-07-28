---
id: mq_consumer_lag
severity: 严重
expected_root_causes:
  - "消费者处理速度跟不上生产速度"
  - "消费者线程阻塞或死锁"
  - "下游依赖服务超时导致消费延迟"
  - "消息大小异常导致处理时间过长"
critical_evidence:
  - "消息积压"
  - "consumer lag >10000"
  - "消费延迟"
mock_alerts:
  - HighMQConsumerLag
mock_logs:
  - application-logs
  - system-events
min_score: 12
---

# 场景：消息队列消费积压告警

## 告警背景
Kafka Topic `order.events` 的消费者组 `order-processor-group` 出现严重积压，
Consumer Lag 达到 15000+，Prometheus 触发 `HighMQConsumerLag` 告警。
订单处理延迟超过 30 分钟，用户投诉订单状态不更新。

## 告警输入文本
```
Kafka order.events 积压 15000 条，订单处理延迟 30 分钟，用户投诉了，帮忙排查
```

## 期望的排查路径
1. 查询 Prometheus 活跃告警 → 确认 `HighMQConsumerLag` 告警
2. 查询应用错误日志 → 查找消费者异常、下游调用超时
3. 分析积压原因 → 消费者线程数不足/下游慢/消息处理逻辑出问题
4. 给出处理方案 → 增加消费者实例、排查阻塞点、临时跳过非关键消息
