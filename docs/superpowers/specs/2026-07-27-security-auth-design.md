# SuperBizAgent 安全与认证 — 技术设计文档

> 版本：v1.0 | 日期：2026-07-27 | 基于 PRD [2026-07-27-superbizagent-security-prd.md](../../session/idea/2026-07-27-superbizagent-security-prd.md)

---

## 目录

1. [架构概述](#1-架构概述)
2. [组件设计](#2-组件设计)
3. [数据流](#3-数据流)
4. [配置设计](#4-配置设计)
5. [API 设计](#5-api-设计)
6. [错误处理](#6-错误处理)
7. [文件清单](#7-文件清单)
8. [测试策略](#8-测试策略)

---

## 1. 架构概述

### 1.1 过滤器链

```
HTTP Request
  │
  ▼
┌─────────────────────────────────────────┐
│  SecurityFilterChain                    │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │ 1. SecurityContextPersistence   │   │   Spring Security 内置
│  │    (Session 管理，API Key 认证  │   │
│  │     不使用 Session)             │   │
│  ├─────────────────────────────────┤   │
│  │ 2. ApiKeyAuthenticationFilter   │   │   自定义 ★
│  │    提取 X-API-Key Header        │   │
│  │    查表验证 → 设置 SecurityCtx  │   │
│  ├─────────────────────────────────┤   │
│  │ 3. RateLimitInterceptor         │   │   自定义 ★
│  │    按 userId 令牌桶计数          │   │
│  ├─────────────────────────────────┤   │
│  │ 4. RequestCacheAwareFilter      │   │   Spring Security 内置
│  ├─────────────────────────────────┤   │
│  │ 5. AuthorizationFilter          │   │   Spring Security 内置
│  │    (通过后放行到 Controller)     │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
  │
  ▼
Controller (userId from SecurityContextHolder)
```

### 1.2 模块依赖

```
security/
├── ApiKeyAuthenticationToken.java   — 认证令牌（extends AbstractAuthenticationToken）
├── ApiKeyAuthenticationFilter.java  — 从 Header 提取 API Key，委托 AuthManager
├── ApiKeyAuthManager.java           — 实际验证逻辑（查配置表）
├── RateLimitInterceptor.java        — 用户级限流（Bucket4j + Caffeine）
└── RateLimitConfig.java             — 限流规则与 Bucket 工厂

config/
├── SecurityConfig.java              — SecurityFilterChain Bean + 白名单配置
├── ApiKeyProperties.java            — @ConfigurationProperties("superbiz.security")
├── SecurityCorsConfig.java          — CORS 配置（替换 WebMvcConfig 中的硬编码）
└── RateLimitConfig.java             — @ConfigurationProperties("superbiz.rate-limit")

controller/
├── AuthController.java              — /api/login 端点

修改：
controller/
├── ChatController.java              — userId 来源改为 SecurityContextHolder
└── MemoryController.java            — userId 来源改为 SecurityContextHolder

config/
├── WebMvcConfig.java                — CORS 改为配置驱动
└── WebConfig.java                   — 注册 RateLimitInterceptor
```

---

## 2. 组件设计

### 2.1 ApiKeyProperties — 配置属性类

```java
@ConfigurationProperties(prefix = "superbiz.security")
@Component
public class ApiKeyProperties {
    private boolean enabled = true;
    private String apiKeyHeader = "X-API-Key";
    private List<ApiKeyEntry> apiKeys = new ArrayList<>();
    private CorsConfig cors = new CorsConfig();

    // getters/setters

    public static class ApiKeyEntry {
        private String key;
        private String userId;
        private String description;
    }

    public static class CorsConfig {
        private List<String> allowedOrigins = List.of("*");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    /** 启动时构建 key→userId 快速查找表 */
    @PostConstruct
    void buildLookupMap() { ... }
}
```

### 2.2 ApiKeyAuthenticationFilter — 认证过滤器

```
职责：从 HTTP 请求中提取 API Key，委托 ApiKeyAuthManager 验证

继承：OncePerRequestFilter（确保每次请求只执行一次）

流程：
  preHandle:
    1. 从 request.getHeader(X-API-Key) 提取 key
    2. 如为 null → 调用 commence() 返回 401
    3. 创建 ApiKeyAuthenticationToken(unauthenticated)
    4. 调用 authManager.authenticate(token)
    5. 成功 → SecurityContextHolder.setContext(token)
    6. 失败 → 调用 commence() 返回 401

白名单路径在 SecurityConfig 中配置，不经过此过滤器
```

### 2.3 ApiKeyAuthManager — 验证管理器

```java
@Component
public class ApiKeyAuthManager implements AuthenticationManager {

    @Override
    public Authentication authenticate(Authentication auth) {
        String apiKey = (String) auth.getCredentials();

        // 1. 在 ApiKeyProperties 的查找表中匹配
        ApiKeyEntry entry = properties.lookup(apiKey);

        // 2. 匹配成功 → 创建已认证 Token
        if (entry != null) {
            ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(
                entry.getUserId(), apiKey, List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            token.setAuthenticated(true);
            return token;
        }

        // 3. 匹配失败 → 抛 BadCredentialsException
        throw new BadCredentialsException("无效的 API Key");
    }
}
```

### 2.4 ApiKeyAuthenticationToken — 认证令牌

```java
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {
    private final String principal;  // userId
    private final String credentials; // apiKey

    // 构造函数
    // - 未认证版本：setAuthenticated(false)
    // - 已认证版本：setAuthenticated(true) + 传入 authorities

    @Override
    public String getName() { return principal; }  // 关键：getName() = userId
}
```

**设计要点**：`getName()` 返回 `userId`，这样 Controller 中通过 `SecurityContextHolder.getContext().getAuthentication().getName()` 即可获取 userId。

### 2.5 RateLimitConfig — 限流配置类

```java
@ConfigurationProperties(prefix = "superbiz.rate-limit")
@Component
public class RateLimitConfig {
    private int chatPerMinute = 30;
    private int chatStreamPerMinute = 10;
    private int aiopsPerMinute = 5;
    private int uploadPerMinute = 10;
    private int memoryPanelPerMinute = 30;
    private int chatClearPerMinute = 10;

    // getters/setters

    /** 构建 path → limit 映射表，供 Interceptor 使用 */
    public Map<String, Integer> buildLimitMap() {
        return Map.of(
            "/api/chat", chatPerMinute,
            "/api/chat_stream", chatStreamPerMinute,
            "/api/ai_ops", aiopsPerMinute,
            "/api/upload", uploadPerMinute,
            "/api/memory/panel", memoryPanelPerMinute,
            "/api/chat/clear", chatClearPerMinute
        );
    }
}
```

### 2.6 RateLimitInterceptor — 用户级限流

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Autowired
    private RateLimitConfig rateLimitConfig;

    // Caffeine Cache: userId:path → Bucket
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder()
        .expireAfterAccess(15, TimeUnit.MINUTES)
        .build();

    // 端点路径 → 每分钟限制次数（从配置注入）
    private Map<String, Integer> limits;

    @PostConstruct
    void init() {
        this.limits = rateLimitConfig.buildLimitMap();
    }

    @Override
    public boolean preHandle(request, response, handler) {
        // 1. 从 SecurityContext 获取 userId
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true; // 未认证不限制

        String userId = auth.getName();
        String path = request.getRequestURI();
        Integer limit = limits.get(path);
        if (limit == null) return true; // 不需要限流的路径

        // 2. 获取或创建令牌桶
        Bucket bucket = bucketCache.get(userId + ":" + path, k -> createBucket(limit));

        // 3. 尝试消费
        if (bucket.tryConsume(1)) return true;

        // 4. 超限 → 429（含 retryAfter 提示）
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(429);
        response.getWriter().write(String.format(
            "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试 (限制: %d 次/分钟)\"}",
            limit
        ));
        return false;
    }

    private Bucket createBucket(int limitPerMinute) {
        return Bucket4j.builder()
            .addLimit(Bandwidth.classic(limitPerMinute,
                Refill.greedy(limitPerMinute, Duration.ofMinutes(1))))
            .build();
    }
}
```

### 2.7 SecurityConfig — Spring Security 核心配置

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationFilter apiKeyFilter,
            ApiKeyProperties properties) throws Exception {

        if (!properties.isEnabled()) {
            // 认证关闭：放行所有请求
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
               .csrf(csrf -> csrf.disable());
            return http.build();
        }

        http
            // 关闭 CSRF（API Key 认证不需要）
            .csrf(csrf -> csrf.disable())

            // 无状态（API Key 每次携带，不需要 Session）
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 权限配置
            .authorizeHttpRequests(auth -> auth
                // 白名单（无需认证）
                .requestMatchers(
                    "/api/login",
                    "/login.html", "/login.js", "/login.css",
                    "/actuator/health",
                    "/milvus/health",
                    "/favicon.ico"
                ).permitAll()
                // API 需要认证
                .requestMatchers("/api/**").authenticated()
                // 静态资源需要认证（保护 SPA）
                .requestMatchers("/index.html", "/app.js", "/styles.css", "/*.js", "/*.css").authenticated()
                .anyRequest().permitAll()
            )

            // 注册自定义过滤器（在 UsernamePasswordAuthenticationFilter 之前）
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)

            // 异常处理
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setContentType("application/json;charset=UTF-8");
                    res.setStatus(401);
                    res.getWriter().write("{\"code\":401,\"message\":\"缺少 API Key\"}");
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setContentType("application/json;charset=UTF-8");
                    res.setStatus(403);
                    res.getWriter().write("{\"code\":403,\"message\":\"无权访问\"}");
                })
            );

        return http.build();
    }
}
```

### 2.8 AuthController — 登录校验接口

```java
@RestController
public class AuthController {

    @Autowired
    private ApiKeyProperties properties;

    /**
     * 校验 API Key 并返回用户信息
     * 此接口本身在白名单中，不需要认证
     */
    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        ApiKeyEntry entry = properties.lookup(request.getApiKey());
        if (entry == null) {
            return ResponseEntity.status(401).body(Map.of(
                "code", 401, "message", "无效的 API Key"
            ));
        }
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", Map.of(
                "userId", entry.getUserId(),
                "description", entry.getDescription()
            )
        ));
    }

    public static class LoginRequest {
        private String apiKey;
        // getter/setter
    }
}
```

---

## 3. 数据流

### 3.1 正常请求流程

```
Client                          Filter Chain                     Controller
  │                                 │                               │
  │  POST /api/chat                │                               │
  │  Header: X-API-Key: sbiz-xxx  │                               │
  │────────────────────────────────▶                               │
  │                                 │                              │
  │                   ApiKeyAuthenticationFilter                   │
  │                   提取 "sbiz-xxx"                               │
  │                   查表 → userId="admin"                        │
  │                   SecurityContext.set("admin")                  │
  │                                 │                              │
  │                   RateLimitInterceptor                         │
  │                   取 userId="admin"                             │
  │                   检查 bucket "admin:/api/chat"                 │
  │                   令牌充足 → 放行                               │
  │                                 │                              │
  │                                 │                              │
  │                                 │  ────────────────────────▶   │
  │                                 │               chat() 方法     │
  │                                 │  userId = SecurityContext     │
  │                                 │           .getAuthentication()│
  │                                 │           .getName()          │
  │                                 │           → "admin"           │
  │                                 │                              │
  │                                 │  ◀────────────────────────   │
  │                                 │               返回 200       │
  │  ◀──────────────────────────────│                              │
  │        200 + JSON 响应          │                              │
```

### 3.2 认证失败流程

```
Client                          Filter Chain
  │                                 │
  │  POST /api/chat                │
  │  (无 X-API-Key Header)         │
  │────────────────────────────────▶
  │                                 │
  │                   ApiKeyAuthenticationFilter
  │                   getHeader("X-API-Key") → null
  │                   ↓
  │                   AuthenticationEntryPoint
  │                   → 401 JSON
  │  ◀──────────────────────────────│
  │  { "code":401,                  │
  │    "message":"缺少 API Key" }    │
```

### 3.3 限流触发流程

```
Client                          Filter Chain
  │                                 │
  │  POST /api/chat (第 31 次)      │
  │  Header: X-API-Key: sbiz-xxx   │
  │────────────────────────────────▶
  │                                 │
  │                   ApiKeyAuthenticationFilter
  │                   ✅ 认证通过
  │                                 │
  │                   RateLimitInterceptor
  │                   bucket.tryConsume(1) → false
  │                   → 429 JSON
  │  ◀──────────────────────────────│
  │  { "code":429,                  │
  │    "message":"请求过于频繁...",  │
  │    "retryAfter": 60 }           │
```

---

## 4. 配置设计

### 4.1 application.yml 新增配置段

```yaml
# ============================================
# SuperBizAgent 安全与认证配置 (v1.0)
# ============================================
superbiz:
  security:
    # === 认证总开关 ===
    # false = 所有接口无需认证（开发/测试环境）
    # true  = 启用 API Key 认证（生产环境）
    enabled: ${SUPERBIZ_SECURITY_ENABLED:false}

    # === API Key 配置 ===
    api-key-header: X-API-Key
    api-keys:
      - key: ${SUPERBIZ_API_KEY_ADMIN:sbiz-admin-key-change-me}
        userId: admin
        description: "系统管理员"
      - key: ${SUPERBIZ_API_KEY_OPERATOR:sbiz-operator-key-change-me}
        userId: operator-01
        description: "运维值班员"

    # === CORS 配置 ===
    cors:
      allowed-origins:
        - ${SUPERBIZ_CORS_ORIGIN:http://localhost:9900}
      allowed-methods:
        - GET
        - POST
        - PUT
        - DELETE
        - OPTIONS

# === 速率限制配置 ===
superbiz:
  rate-limit:
    chat-per-minute: 30
    chat-stream-per-minute: 10
    aiops-per-minute: 5
    upload-per-minute: 10
    memory-panel-per-minute: 30
    chat-clear-per-minute: 10
```

### 4.2 dev profile 覆盖

```yaml
# application-dev.yml
superbiz:
  security:
    enabled: false  # 开发环境关闭认证
```

### 4.3 prod profile 覆盖

```yaml
# application-prod.yml
superbiz:
  security:
    enabled: true
    cors:
      allowed-origins:
        - "https://superbiz.example.com"
```

---

## 5. API 设计

### 5.1 新增端点

| 方法 | 路径 | 认证 | 限流 | 说明 |
|------|------|------|------|------|
| POST | `/api/login` | 无 | 否 | 校验 API Key，返回用户信息 |

**请求体：**
```json
{ "apiKey": "sbiz-admin-key-change-me" }
```

**成功响应（200）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": "admin",
    "description": "系统管理员"
  }
}
```

**失败响应（401）：**
```json
{ "code": 401, "message": "无效的 API Key", "data": null }
```

### 5.2 变更端点

| 方法 | 路径 | 变更内容 |
|------|------|---------|
| POST | `/api/chat` | 请求体去掉 `UserId` 字段；服务端从 SecurityContext 获取 |
| POST | `/api/chat_stream` | 同上 |
| GET  | `/api/memory/panel` | 去掉 `userId` 查询参数 |
| DELETE | `/api/memory/{id}` | 去掉 `userId` 查询参数 |
| DELETE | `/api/memory/clear` | 去掉 `userId` 查询参数 |

### 5.3 ChatController 变更细节

```java
// 变更前
@PostMapping("/chat")
public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
    String userId = request.getUserId();  // ← 客户端传入，不安全
    ...
}

// 变更后
@PostMapping("/chat")
public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String userId = auth != null ? auth.getName() : "anonymous";
    ...
}

// ChatRequest 去掉 UserId 字段
public static class ChatRequest {
    @JsonProperty("Id")    private String Id;
    @JsonProperty("Question") private String Question;
    // 删除 UserId 字段
}
```

### 5.4 MemoryController 变更细节

```java
// 变更前
@GetMapping("/panel")
public ResponseEntity<Map<String, Object>> getMemoryPanel(@RequestParam("userId") String userId) { ... }

// 变更后
@GetMapping("/panel")
public ResponseEntity<Map<String, Object>> getMemoryPanel() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String userId = auth.getName();
    ...
}
```

---

## 6. 错误处理

### 6.1 错误响应格式

所有安全相关错误统一格式（与现有 `ApiResponse` 对齐）：

```json
{
  "code": <HTTP_STATUS>,
  "message": "<可读的错误信息>",
  "data": null
}
```

### 6.2 错误码表

| 场景 | HTTP | 响应示例 |
|------|------|---------|
| 缺少 X-API-Key | 401 | `{"code":401,"message":"缺少 API Key，请在请求头中携带 X-API-Key"}` |
| 无效 API Key | 401 | `{"code":401,"message":"无效的 API Key"}` |
| 超限 `/api/chat` | 429 | `{"code":429,"message":"请求过于频繁，请稍后再试 (限制: 30 次/分钟)"}` |
| 超限 `/api/chat_stream` | 429 | `{"code":429,"message":"请求过于频繁，请稍后再试 (限制: 10 次/分钟)"}` |
| 超限 `/api/ai_ops` | 429 | `{"code":429,"message":"请求过于频繁，请稍后再试 (限制: 5 次/分钟)"}` |

### 6.3 安全信息脱敏

- `LogInterceptor` 中打印请求头时，**对 `X-API-Key` 的值进行脱敏**（只显示前 4 位 + `****`）
- `401` 错误响应不包含收到的 API Key 原文（防止暴力枚举）

---

## 7. 文件清单

### 7.1 新增文件（15 个）

```
后端 (9 个):
  src/main/java/org/example/
    config/SecurityConfig.java              — Spring Security FilterChain
    config/ApiKeyProperties.java            — @ConfigurationProperties
    config/SecurityCorsConfig.java          — CORS WebMvcConfigurer
    security/ApiKeyAuthenticationFilter.java — OncePerRequestFilter
    security/ApiKeyAuthenticationToken.java  — AbstractAuthenticationToken
    security/ApiKeyAuthManager.java          — AuthenticationManager
    security/RateLimitInterceptor.java       — HandlerInterceptor
    security/RateLimitConfig.java            — 限流规则配置类（读 yaml，注入限流参数）
    controller/AuthController.java           — /api/login

前端 (3 个):
  src/main/resources/static/
    login.html                              — 登录页面
    login.js                                — 登录逻辑
    login.css                               — 登录页样式

配置 (3 个):
  src/main/resources/
    application-dev.yml                     — 新增（dev profile：关闭认证）
    application-prod.yml                    — 新增（prod profile：开启认证 + CORS 白名单）
```

### 7.2 修改文件（6 个）

```
  src/main/java/org/example/
    controller/ChatController.java          — userId 来源改造
    controller/MemoryController.java        — userId 来源改造
    config/WebMvcConfig.java                — CORS 配置化
    config/WebConfig.java                   — 注册 RateLimitInterceptor

  src/main/resources/
    application.yml                         — 新增安全配置段 + 限流配置段

  src/main/resources/static/
    index.html                              — auth guard（检查 localStorage API Key）
    app.js                                  — X-API-Key Header + 401/429 处理
```

### 7.3 依赖变更（pom.xml）

```xml
<!-- 新增：Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- 新增：Bucket4j 令牌桶限流 -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
```

---

## 8. 测试策略

### 8.1 单元测试

| 测试类 | 覆盖内容 |
|--------|---------|
| `ApiKeyAuthManagerTest` | 有效 key 认证成功、无效 key 抛异常、空 key 抛异常 |
| `ApiKeyAuthenticationFilterTest` | 带有效 Header 放行、无 Header 返回 401、无效 Header 返回 401 |
| `RateLimitInterceptorTest` | 未超限放行、超限返回 429、不同用户独立计数 |
| `ApiKeyPropertiesTest` | lookupMap 构建正确性、enabled 开关行为 |
| `AuthControllerTest` | 有效 key 返回 200+用户信息、无效 key 返回 401 |

### 8.2 集成测试

| 测试类 | 覆盖内容 |
|--------|---------|
| `SecurityIntegrationTest` | `@SpringBootTest` + `MockMvc`，验证认证和限流全链路 |
| `ChatControllerAuthTest` | 带/不带 API Key 的 chat 请求、userId 从 SecurityContext 读取 |
| `MemoryControllerAuthTest` | 记忆接口认证校验、用户 A 不能操作用户 B 的记忆 |

### 8.3 手工测试

| 场景 | 步骤 |
|------|------|
| 登录流程 | 访问 index.html → 跳转 login.html → 输入正确/错误 API Key |
| 认证错误 | 不带 Header 调 /api/chat → 401 |
| 限流触发 | 快速连续调 /api/chat 30+ 次 → 429 |
| dev 兼容 | `enabled=false` → 无需 API Key 正常工作 |

---

## 附录 A：与现有代码的兼容策略

### 认证关闭模式

当 `superbiz.security.enabled=false` 时：
- `SecurityFilterChain` 配置为全部放行（`anyRequest().permitAll()`）
- `ApiKeyAuthenticationFilter` 不注册
- `RateLimitInterceptor` 不注册
- Controller 中 `SecurityContextHolder` 返回 null → 使用 "anonymous" 作为默认 userId
- **所有现有功能保持不变**

### 渐进升级路径

```
v1.0 (本次):  配置文件 API Key + Spring Security Filter + Bucket4j 限流
v1.1 (未来):  API Key 存入数据库 + 管理 UI
v2.0 (未来):  JWT Token + 角色权限（admin/operator/viewer）
v3.0 (未来):  OAuth2/SSO 企业统一认证
```
