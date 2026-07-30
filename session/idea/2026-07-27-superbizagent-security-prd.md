# SuperBizAgent 安全与认证 — 产品需求文档 (PRD)

> 版本：v1.0 | 日期：2026-07-27 | 基于改进方案 P0-3 展开

---

## 1. 背景与问题

### 1.1 现状

SuperBizAgent 当前没有任何认证机制，存在以下安全风险：

| 风险项 | 严重程度 | 现状 |
|--------|---------|------|
| 无身份认证 | 🔴 严重 | 任何人可直接调用所有 API |
| CORS 开放所有来源 | 🔴 严重 | `allowedOrigins("*")`，任意域名可跨域调用 |
| userId 客户端随意传入 | 🟠 高 | 可冒充他人读取/删除/清空记忆和会话 |
| LLM 端点无速率限制 | 🟠 高 | 可被耗尽 API 配额，产生大量费用 |
| 记忆数据无访问控制 | 🟠 高 | 知道 userId 即可操作任意用户的记忆 |

### 1.2 目标

建立 API Key 认证体系，实现以下核心目标：

1. **身份认证**：所有 `/api/**` 请求必须携带有效 API Key，否则拒绝访问
2. **多用户隔离**：每个 API Key 绑定一个 userId，用户数据（会话、记忆）彼此隔离
3. **速率限制**：按用户对 LLM 消耗类接口进行速率限制，防止配额滥用
4. **CORS 安全**：生产环境收紧跨域来源为白名单域名
5. **前端防护**：新增登录页面，API Key 由用户主动输入，前端不暴露 userId

---

## 2. 功能需求

### F1. API Key 认证

**F1.1 API Key 管理**

- API Key 在 `application.yml` 中以 map 形式配置，格式：`key → (userId, description)`
- 支持多个 API Key，每个绑定唯一的 userId
- 系统启动时加载到内存，运行时不支持热更新（v1 范围）
- 认证总开关 `superbiz.security.enabled`，设为 `false` 时走默认匿名用户（便于开发）

**F1.2 认证流程**

- 客户端在所有 `/api/**` 请求中携带请求头 `X-API-Key: <key_value>`
- 服务端通过 Spring Security Filter 提取并校验 API Key
- 校验成功：将 userId 写入 SecurityContext，后续 Controller 从 SecurityContext 读取
- 校验失败：返回 HTTP 401 + JSON 错误体 `{"code":401, "message":"无效的 API Key"}`
- 缺少请求头：返回 HTTP 401 + JSON 错误体 `{"code":401, "message":"缺少 API Key"}`

**F1.3 WhiteList（无需认证的路径）**

以下路径不经过 API Key 校验：

| 路径 | 原因 |
|------|------|
| `/api/login` | 登录校验接口本身 |
| `/login.html`, `/login.js`, `/login.css` | 登录页面静态资源 |
| `/actuator/health` | 健康检查（K8s 探针） |
| `/milvus/health` | Milvus 连通性检查 |
| `/favicon.ico` | 浏览器图标 |

### F2. 用户速率限制

**F2.1 限流策略**

按用户（即 API Key 对应的 userId）独立计数：

| 端点 | 限制 | 说明 |
|------|------|------|
| `POST /api/chat` | 30 次/分钟 | 普通对话，最常用 |
| `POST /api/chat_stream` | 10 次/分钟 | SSE 流式，资源消耗更大 |
| `POST /api/ai_ops` | 5 次/分钟 | AIOps 分析，Token 消耗最大 |
| `POST /api/upload` | 10 次/分钟 | 文件上传（补充已有的 IP 级限流） |
| `GET /api/memory/panel` | 30 次/分钟 | 记忆面板查询 |
| `POST /api/chat/clear` | 10 次/分钟 | 清空会话 |

**F2.2 限流实现**

- 使用 Caffeine Cache 为每个 userId 维护独立的 Bucket4j 令牌桶
- 桶在用户首次请求时自动创建，闲置 15 分钟后自动过期回收
- 超限返回 HTTP 429 + JSON 错误体，包含 `retryAfter` 秒数提示

**F2.3 限流错误响应**

```json
{
  "code": 429,
  "message": "请求过于频繁，请 60 秒后再试",
  "data": { "retryAfter": 60 }
}
```

### F3. CORS 安全加固

- 生产环境 `allowedOrigins` 必须配置为具体域名白名单（如 `https://superbiz.example.com`）
- 开发环境（`spring.profiles.active=dev`）可保持 `allowedOrigins("*")`
- 白名单域名通过 `superbiz.security.cors.allowed-origins` 配置
- `allowedMethods` 限制为 `GET, POST, PUT, DELETE, OPTIONS`

### F4. Controller userId 来源改造

**F4.1 改造清单**

| Controller 方法 | 当前 userId 来源 | 改造后 userId 来源 |
|:---|:---|:---|
| `ChatController.chat()` | `ChatRequest.UserId` 字段 | `SecurityContextHolder.getContext().getAuthentication().getName()` |
| `ChatController.chatStream()` | `ChatRequest.UserId` 字段 | 同上 |
| `MemoryController.getMemoryPanel()` | `@RequestParam userId` | 同上 |
| `MemoryController.deleteMemory()` | `@RequestParam userId` | 同上 |
| `MemoryController.clearMemories()` | `@RequestParam userId` | 同上 |

**F4.2 请求体变更**

- `ChatRequest` 去掉 `UserId` 字段
- `MemoryController` 各方法去掉 `@RequestParam userId` 参数
- 前端不再发送 `UserId`

### F5. 前端登录页面

**F5.1 登录页（login.html）**

- 简洁的单页面：产品名称 + API Key 输入框 + 登录按钮
- 输入 API Key → `POST /api/login` 校验
- 校验成功 → `localStorage` 保存 API Key → 跳转 `index.html`
- 校验失败 → 显示红色错误提示
- 无 API Key 的用户看到 "联系管理员获取" 提示

**F5.2 主页面改造（index.html / app.js）**

- 页面加载时检查 `localStorage` 中是否有 API Key
  - 无：跳转 `login.html`
  - 有：正常渲染
- 所有 `fetch()` 请求统一添加 `X-API-Key` 请求头
- 收到 HTTP 401 → 清除 API Key → 跳转 `login.html`
- 收到 HTTP 429 → 显示 toast 提示 "请求太频繁，请稍后再试"
- `getUserId()` 方法移除（不再从客户端生成或传递 userId）

**F5.3 登录接口**

```
POST /api/login
Content-Type: application/json

Request:  { "apiKey": "sbiz-xxxxxxxxxxxx" }
Success:  { "code": 200, "data": { "userId": "admin", "description": "管理员" } }
Failure:  { "code": 401, "message": "无效的 API Key" }
```

### F6. 错误响应格式统一

所有安全相关错误统一使用与现有 `ApiResponse` 兼容的 JSON 格式：

| HTTP 状态码 | 触发场景 |
|:---|:---|
| 401 | 缺少 X-API-Key 请求头 |
| 401 | API Key 无效（不在配置列表中） |
| 429 | 超过速率限制 |
| 403 | 无权访问资源（v2 扩展预留） |

---

## 3. 非功能需求

### N1. 兼容性

- 通过 `superbiz.security.enabled=false` 关闭认证后，系统完全回退到当前行为（dev 环境默认关闭）
- 现有 `/api/chat`、`/api/chat_stream`、`/api/ai_ops` 的功能逻辑不受影响
- 文件上传已有 IP 级限流（Resilience4j），新增用户级限流与之互补不冲突

### N2. 性能

- API Key 校验在内存中完成（O(1) HashMap 查找），延迟可忽略（< 1ms）
- 限流桶使用 Caffeine 内存缓存，延迟 < 0.1ms
- 整体请求增加延迟 < 2ms

### N3. 安全

- API Key 不在日志中打印（RateLimitInterceptor/LogInterceptor 应脱敏 Header 值）
- API Key 不在错误响应中回显
- 前端仅将 API Key 存储在 `localStorage`（v1 范围，未来可考虑 sessionStorage）
- 生产环境强制 HTTPS（运维层面保障）

### N4. 可维护性

- API Key 配置集中在一个 yaml 节点下
- 认证开关一键开启/关闭
- 限流参数均可配置

---

## 4. 验收标准

### AC1. 认证功能

- [ ] 不带 `X-API-Key` 请求 `/api/chat` 返回 401
- [ ] 带无效 `X-API-Key` 请求 `/api/chat` 返回 401
- [ ] 带有效 `X-API-Key` 请求 `/api/chat` 正常返回 200
- [ ] 健康检查 `/actuator/health` 无需 API Key 即可访问
- [ ] `superbiz.security.enabled=false` 时所有接口无需 API Key 正常工作

### AC2. 用户隔离

- [ ] 用户 A 的 API Key 只能操作用户 A 的会话和记忆
- [ ] `ChatRequest` 不再包含 `UserId` 字段
- [ ] `MemoryController` 不再接收 `userId` 请求参数

### AC3. 速率限制

- [ ] 同一用户 1 分钟内调用 `/api/chat` 超过 30 次返回 429
- [ ] 限流响应包含 `retryAfter` 提示
- [ ] 不同用户的限流独立计数

### AC4. CORS

- [ ] 生产 profile 下 `allowedOrigins` 为配置的白名单域名
- [ ] dev profile 下 `allowedOrigins("*")` 保持不变

### AC5. 前端

- [ ] 访问 `index.html` 无 API Key 时自动跳转 `login.html`
- [ ] `login.html` 输入正确 API Key 后成功跳转 `index.html`
- [ ] `login.html` 输入错误 API Key 后显示错误提示
- [ ] 主页面收到 401 时自动退回登录页

### AC6. 兼容性

- [ ] 关闭认证开关后，所有现有功能正常工作
- [ ] 文件上传的 IP 级限流不受影响

---

## 5. 范围边界

### v1 范围内

- API Key 配置文件管理（yaml）
- Spring Security 认证过滤器
- Bucket4j 用户级限流
- CORS 生产环境收紧
- 登录页面 + 主页面适配
- Controller userId 来源改造

### v1 范围外（后续版本）

- API Key 数据库存储与管理 UI
- API Key 动态创建/吊销
- 方法级权限控制（`@PreAuthorize`）
- JWT Token 方案
- OAuth2/SSO 集成
- 审计日志
- API Key 加密存储
- 前端记住登录状态（sessionStorage vs localStorage）

---

## 6. 风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Spring Security 自动配置影响现有接口 | 现有功能异常 | 充分测试白名单路径，提供关闭开关 |
| 前端改造引入兼容性问题 | 老用户访问异常 | 开发 profile 默认关闭认证 |
| API Key 配置文件泄露 | 安全事件 | 生产环境通过环境变量注入，不写入镜像 |
| 限流参数不合理 | 正常用户被误拦 | 参数可配置，提供宽松默认值 |
