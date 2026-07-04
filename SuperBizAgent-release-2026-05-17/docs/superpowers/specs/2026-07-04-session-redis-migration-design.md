# 会话记忆 Redis 化 — 设计方案

**日期**: 2026-07-04
**状态**: 待评审
**范围**: ChatController 会话存储从 ConcurrentHashMap 迁移至 Redis，新增 LLM 摘要层

---

## 1. 背景与目标

### 当前状态
- `ChatController` 使用 `ConcurrentHashMap<String, SessionInfo>` 存储所有用户的会话记忆
- `SessionInfo` 是 ChatController 的私有静态内部类，含消息历史列表、ReentrantLock、创建时间
- 会话数据仅存于 JVM 内存，进程重启即丢失，无法水平扩展

### 目标
1. 会话数据持久化至 Redis，支持多实例部署
2. 新增 LLM 驱动的对话摘要层，减少长对话的 token 消耗
3. 所有行为通过 `application.yml` 配置，保持灵活性

---

## 2. 架构总览

```
┌─────────┐     REST API      ┌─────────────┐     Redis     ┌─────────┐
│  前端    │ ◄──────────────► │ ChatController │ ◄────────► │  Redis   │
│ (SPA)   │  /api/chat        │              │             │ Container│
└─────────┘  /api/chat_stream │   ┌───────────────────┐    └─────────┘
                              │   │ SessionManager    │
                              │   │ (新建 Service)     │
                              │   │ - getContext()    │
                              │   │ - addMessage()    │
                              │   │ - triggerSummary()│
                              │   └───────────────────┘
                              │         │
                              │         ▼ (异步)
                              │   ┌───────────────────┐
                              │   │ SummaryGenerator  │
                              │   │ - 调用 LLM 生成摘要 │
                              │   └───────────────────┘
                              └─────────────┘
```

**核心改造**: 把 ChatController 中的 `ConcurrentHashMap` + `SessionInfo` 内部类抽成独立的 `SessionManager` Service，底层替换为 Redis。同时增加 `SummaryGenerator`，在历史消息过长时异步调用 LLM 压缩上下文。

---

## 3. Redis 数据模型

### Key 设计（三层，按优先级查询）

| 层 | Key 格式 | Value | TTL |
|---|---|---|---|
| 摘要层 | `session:{id}:summary` | JSON `{"summary":"...", "summaryTime":..., "summarizedMessageCount":N}` | 可配（默认24h） |
| 详情层 | `session:{id}:history` | JSON 数组 `[{"role":"user","content":"..."}, ...]` | 可配（默认24h） |
| 元数据 | `session:{id}:meta` | JSON `{"createTime":..., "messagePairCount":N, "lastAccessTime":...}` | 可配（默认24h） |

### 规则

- 摘要和详情**共存** — 摘要生成成功后，详情层保留不删除。读取时摘要优先（节省 token），详情层作为完整记录保留，可用于后续重新生成摘要或调试
- 如果 `summary.enabled: false`，既不查询摘要层，也不触发摘要生成，始终使用详情层
- 每次读写刷新所有相关 key 的 TTL
- 摘要的 TTL 继承 session 配置，和详情保持一致的过期策略（独立计时）

---

## 4. 读写路径

### 获取上下文（/chat、/chat_stream）

```
getOrCreateSession(sessionId)
  │
  ├─ 1. 如果 summary.enabled == true:
  │     └─ 查 session:{id}:summary (摘要层)
  │           └─ 命中 → 直接返回摘要，作为 system prompt 的"对话历史摘要"部分
  │
  └─ 2. 摘要未命中或 summary.enabled == false:
        └─ 查 session:{id}:history (详情层)
              ├─ 命中 → 返回原始消息列表，拼入 system prompt
              └─ 未命中 → 新会话，返回空历史
```

### 添加消息（/chat、/chat_stream 完成时）

```
addMessage(sessionId, userMsg, aiMsg)
  │
  ├─ 1. 追加到 session:{id}:history（JSON 数组尾部追加2条）
  ├─ 2. 更新 session:{id}:meta（messagePairCount + lastAccessTime）
  ├─ 3. 重置所有 key 的 TTL
  │
  └─ 4. 如果 summary.enabled == true 且 messagePairCount > summary.trigger-threshold:
        └─ 异步触发 generateSummary(sessionId)
              │
              ├─ a. 取 session:{id}:history 全部消息
              ├─ b. 调用 LLM 压缩为摘要（temperature=0.3）
              ├─ c. 写入 session:{id}:summary（详情层保留不删除）
              └─ d. 更新 session:{id}:meta，记录摘要生成时间
```

### 清空会话（/chat/clear）

```
clearSession(sessionId)
  └─ 同时删除 session:{id}:summary + session:{id}:history + session:{id}:meta
```

### 摘要开关行为

| `summary.enabled` | 摘要层已存在 | 摘要层不存在 |
|---|---|---|
| `true` | 用摘要作为上下文 → system prompt | 查详情层，拼原始历史 |
| `false` | 跳过摘要，直接查详情层 | 查详情层，拼原始历史 |

---

## 5. 配置项

```yaml
# application.yml 新增
session:
  redis:
    ttl-hours: 24              # 会话过期时间（小时），0=永不过期，默认24
    summary:
      enabled: true            # 是否查询摘要层，false=跳过摘要直接查详情
      trigger-threshold: 10    # 消息对数超过此阈值触发摘要生成
      model: "qwen3-max"       # 生成摘要用的 LLM 模型
      max-summary-length: 500  # 摘要最大字符数

spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:               # 留空=无密码
      timeout: 3000ms
```

---

## 6. 异步摘要生成

```
SummaryGenerator (新建 Service)
  │
  ├─ @Async 自定义线程池异步执行
  │
  ├─ trigger(sessionId):
  │    1. 查 session:{id}:history
  │    2. 检查是否已在生成中（Redis SETNX 分布式锁，60s 过期）
  │    3. 调用 ChatService → DashScopeChatModel（temperature=0.3）
  │       Prompt: "请将以下对话历史压缩为一段不超过{maxLen}字的摘要..."
  │    4. 写入 session:{id}:summary（覆盖旧摘要，如有）
  │    5. 更新 session:{id}:meta，记录摘要时间戳
  │    6. 释放锁
  │
  └─ 失败处理：写日志，锁自动过期，下次请求重试
```

关键点:
- `@EnableAsync` + 自定义 `ThreadPoolTaskExecutor`，不阻塞主请求
- Redis `SETNX` 防并发重复生成
- 详情层（history）始终保留，不会被摘要生成过程删除

---

## 7. 响应格式变更

### /api/chat（非流式）— ChatResponse 增加 sessionId

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "answer": "你好！有什么可以帮助你的？",
    "sessionId": "session_abc123_1704398400"
  }
}
```

### /api/chat_stream（SSE 流式）— done 事件增加 sessionId

```
event: message
data: {"type":"done","data":null,"sessionId":"session_abc123_1704398400"}
```

如果后端生成了新的 sessionId（客户端未传），通过此字段告知前端。如果客户端已传 sessionId，后端原样返回。

---

## 8. 系统提示词适配

`ChatService.buildSystemPrompt()` 需要适配两种上下文来源：

- **摘要模式**: 系统提示词中加入 "以下是此前对话的摘要：{summary}" — 不包含原始消息
- **详情模式**: 保持现有行为，拼接原始消息列表作为 "对话历史"

---

## 9. 文件变更清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `pom.xml` | 改 | 新增 `spring-boot-starter-data-redis` 依赖 |
| `application.yml` | 改 | 新增 Redis 连接 + session 配置块 |
| `vector-database.yml` | 改 | 新增 Redis 容器（redis:7-alpine, port 6379） |
| `Makefile` | 改 | 适配 Redis 容器启动 |
| `SessionManager.java` | **新建** | Session 三层 Redis CRUD + TTL + 摘要触发逻辑 |
| `SummaryGenerator.java` | **新建** | 异步 LLM 摘要生成，含 SETNX 锁 |
| `ChatController.java` | 改 | 去掉 ConcurrentHashMap + SessionInfo 内部类，注入 SessionManager；响应回传 sessionId |
| `ChatService.java` | 改 | buildSystemPrompt() 支持摘要模式 |
| `RedisConfig.java` | **新建** | RedisTemplate + Jackson2JsonRedisSerializer |
| `app.js` | 改 | 从响应中读取 sessionId 并存储 |

---

## 10. 边界情况

| 场景 | 处理 |
|---|---|
| Redis 不可用 | Spring Boot 启动失败（默认）。可通过配置 fail-fast: false 降级 |
| 摘要生成中，新消息到达 | SETNX 锁保护；新消息正常追加到 history，不影响摘要生成过程 |
| 摘要多次生成（消息持续增长） | 每次超过阈值时重新生成摘要，覆盖旧摘要；详情层始终保留完整历史 |
| 客户端不传 sessionId | 后端生成 UUID 作为新 sessionId，通过响应告知客户端 |
| TTL=0（永不过期） | Redis key 不设过期，和当前 ConcurrentHashMap 行为一致 |
| 清空会话 | 同时删除 summary + history + meta 三个 key |
| 摘要质量不佳或失真的回退 | 摘要层仅作辅助；可关闭 summary.enabled 回退到详情模式 |

---

## 11. 新增依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Jackson 已有，无需额外引入。

---

## 12. Docker Compose 新增

```yaml
# vector-database.yml 新增
redis:
  container_name: super-biz-redis
  image: redis:7-alpine
  ports:
    - "6379:6379"
  volumes:
    - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/redis:/data
  command: redis-server --appendonly yes
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 5s
    retries: 3
```
