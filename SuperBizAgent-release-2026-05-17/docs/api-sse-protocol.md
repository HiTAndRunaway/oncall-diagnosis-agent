# SSE 流式协议规范 v1

> 版本: 1.0 | 日期: 2026-08-01

## 连接信息

- **端点**: `POST /api/v1/chat_stream`
- **Content-Type**: `application/json`
- **Accept**: `text/event-stream`
- **超时**: 5 分钟（300 秒）

## 事件类型

| SSE event | 说明 | 数据格式 |
|-----------|------|----------|
| `message` | 通用消息容器 | `AgentEvent` JSON |
| `content` | 文本增量 | `{"type":"CONTENT_CHUNK","data":"文本片段"}` |
| `tool_start` | 工具调用开始 | `{"type":"TOOL_CALL_START","data":"工具名称"}` |
| `tool_end` | 工具调用结束 | `{"type":"TOOL_CALL_END","data":"结果摘要"}` |
| `error` | 错误事件 | `{"type":"ERROR","data":"错误描述"}` |
| `done` | 流完成 | `{"type":"DONE","data":null,"sessionId":"会话ID"}` |

## AgentEvent 数据结构

```json
{
  "type": "CONTENT_CHUNK | TOOL_CALL_START | TOOL_CALL_END | ERROR | DONE",
  "data": "具体数据内容",
  "sessionId": "会话ID（仅 DONE 事件携带）"
}
```

## 完整交互示例

### 请求
```http
POST /api/v1/chat_stream HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{"Id":"session-abc","Question":"帮我查一下CPU告警"}
```

### 响应流
```
event:message
data:{"type":"CONTENT_CHUNK","data":"正在"}

event:message
data:{"type":"CONTENT_CHUNK","data":"查询"}

event:message
data:{"type":"TOOL_CALL_START","data":"queryPrometheusAlerts"}

event:message
data:{"type":"TOOL_CALL_END","data":"{\"status\":\"success\"}"}

event:message
data:{"type":"CONTENT_CHUNK","data":"当前没有活跃的CPU告警。"}

event:message
data:{"type":"DONE","data":null,"sessionId":"session-abc"}
```

## AIOps SSE 端点

- **端点**: `POST /api/v1/ai_ops`
- **超时**: 10 分钟
- **事件格式**: 同上，但内容为告警分析报告的逐块输出
