# Implementation Plan: Security & Authentication

> Based on [design doc](../specs/2026-07-27-security-auth-design.md)

## Global Constraints

- Spring Boot 3.2 + Java 17
- Maven project under `SuperBizAgent-release-2026-05-17/`
- Base package: `org.example`
- All ApiResponse errors: `{"code": <int>, "message": "<string>", "data": null}`
- Config prefix: `superbiz.security.*` (ApiKeyProperties), `superbiz.rate-limit.*` (RateLimitConfig)
- X-API-Key header name for API key
- Default security disabled for dev; enabled flag: `superbiz.security.enabled`
- All new Java classes go under existing package structure (`org.example.config`, `org.example.security`, `org.example.controller`)
- Frontend files under `src/main/resources/static/`
- Existing tests: none (no `src/test/` directory yet — tests are optional for this plan, verification by compile + manual)
- Don't break existing functionality when `superbiz.security.enabled=false`
- Keep existing file patterns and code style

## Task List

### Task 1: Security Infrastructure (Backend)

**Files to create:**
1. `src/main/java/org/example/config/ApiKeyProperties.java` — `@ConfigurationProperties("superbiz.security")` with `enabled`, `apiKeyHeader`, `apiKeys` list, `CorsConfig`, plus `lookup()` method
2. `src/main/java/org/example/security/ApiKeyAuthenticationToken.java` — extends `AbstractAuthenticationToken`, `getName()` returns userId
3. `src/main/java/org/example/security/ApiKeyAuthManager.java` — implements `AuthenticationManager`, looks up API key in `ApiKeyProperties`
4. `src/main/java/org/example/security/ApiKeyAuthenticationFilter.java` — extends `OncePerRequestFilter`, extracts `X-API-Key` header, delegates to `ApiKeyAuthManager`
5. `src/main/java/org/example/config/SecurityConfig.java` — `@EnableWebSecurity`, `SecurityFilterChain` bean with whitelist, stateless session, CSRF disabled, auth entry point (401 JSON), access denied handler (403 JSON)
6. `src/main/java/org/example/config/RateLimitConfig.java` — `@ConfigurationProperties("superbiz.rate-limit")` with per-endpoint limits
7. `src/main/java/org/example/security/RateLimitInterceptor.java` — `HandlerInterceptor`, Caffeine cache of Bucket4j buckets, keyed by `userId:path`
8. `src/main/java/org/example/config/SecurityCorsConfig.java` — `WebMvcConfigurer`, reads CORS from `ApiKeyProperties`
9. `src/main/java/org/example/controller/AuthController.java` — `POST /api/login`, validates API key, returns userId+description

**Files to modify:**
10. `pom.xml` — add `spring-boot-starter-security` and `bucket4j-core` dependencies
11. `src/main/resources/application.yml` — add `superbiz.security` and `superbiz.rate-limit` config sections
12. `src/main/resources/application-dev.yml` — NEW: `superbiz.security.enabled: false`
13. `src/main/resources/application-prod.yml` — NEW: `superbiz.security.enabled: true` + CORS whitelist

**Verification:** `mvn compile` passes, all new classes compile without errors.

### Task 2: Controller Updates

**Files to modify:**
1. `src/main/java/org/example/controller/ChatController.java`
   - `chat()` method: replace `request.getUserId()` with `SecurityContextHolder.getContext().getAuthentication().getName()` 
   - `chatStream()` method: same replacement in the executor lambda
   - `ChatRequest` inner class: remove `UserId` field
2. `src/main/java/org/example/controller/MemoryController.java`
   - `getMemoryPanel()`: replace `@RequestParam("userId")` with SecurityContext lookup
   - `deleteMemory()`: same
   - `clearMemories()`: same
3. `src/main/java/org/example/config/WebMvcConfig.java` — remove hardcoded `allowedOrigins("*")`, delegate to `SecurityCorsConfig` (or make CORS conditional on profile)
4. `src/main/java/org/example/config/WebConfig.java` — register `RateLimitInterceptor` in `addInterceptors()`

**Verification:** `mvn compile` passes, all existing method signatures that don't change compile.

### Task 3: Frontend

**Files to create:**
1. `src/main/resources/static/login.html` — clean login page with API Key input + submit button
2. `src/main/resources/static/login.js` — form handler, POST `/api/login`, localStorage save, redirect
3. `src/main/resources/static/login.css` — login page styling matching existing design

**Files to modify:**
4. `src/main/resources/static/index.html` — add auth guard script (check localStorage for apiKey, redirect to login.html if missing)
5. `src/main/resources/static/app.js` — add `X-API-Key` header to all fetch calls, handle 401 (redirect to login), handle 429 (show toast), remove `getUserId()` method references

**Verification:** `mvn compile` passes. Frontend files are valid HTML/JS/CSS.

### Task 4: Integration Verification

- `mvn compile` confirms all changes compile together
- No regression: `superbiz.security.enabled=false` (dev profile) means all existing behavior unchanged

## Dependencies

- Task 2 depends on Task 1 (needs security classes)
- Task 3 depends on Task 1 (needs API contract and header name)
- Task 4 depends on all previous tasks
