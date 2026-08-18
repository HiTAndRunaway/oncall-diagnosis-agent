# Docker 部署指南

> 面向不熟悉 Docker 的使用者。本文回答两个问题：
> 1. 镜像如何推送到 GitHub？
> 2. 推送后如何用镜像部署服务？

---

## 0. 结论先说

- **镜像推送是自动的**：代码合并到 `master` 后，GitHub Actions 会自动构建镜像并推送到 GitHub Container Registry（GHCR）。**你不需要手动执行 `docker push`**。
- **部署只需一条 `docker run`**：从 GHCR 拉取镜像并运行，密钥（`DASHSCOPE_API_KEY`）通过 `-e` 环境变量在运行时注入，绝不写进镜像。

---

## 1. 镜像信息

| 项目 | 值 |
|------|----|
| 镜像地址 | `ghcr.io/hitandrunaway/oncall-diagnosis-agent` |
| 常用标签 | `latest`（最新）、`<commit-sha>`（每次提交对应一个版本） |
| 完整示例 | `ghcr.io/hitandrunaway/oncall-diagnosis-agent:latest` |
| 可见性 | **公开（public）**，任何人都可 pull |

> 镜像名全小写是 GHCR 的硬性要求，本仓库 `HiTAndRunaway/oncall-diagnosis-agent` 对应小写 `hitandrunaway/oncall-diagnosis-agent`。

---

## 2. 镜像是如何自动推送的（背景）

仓库内的 `.github/workflows/ci.yml` 做了三件事：

1. **每次 push / PR 到 `master`**：用 Maven 编译打包（`mvn -DskipTests package`）验证代码可构建。
2. **仅当 push 到 `master`**：登录 GHCR → 构建 Docker 镜像 → 推送 `:latest` 和 `:<sha>` 两个标签。
3. **PR 只构建、不推送**：合入 master 前先验证，不会污染镜像仓库。

所以你的流程是：**把 feature 分支合并到 `master` → CI 自动出镜像**，无需手动操作。

---

## 3. 部署前准备

1. 安装 Docker（Windows 用 Docker Desktop，确认 `docker version` 能跑通）。
2. 准备一个 DashScope API Key（`sk-` 开头）。
3. 启动依赖基础设施：Milvus 向量库（可用项目里的 `make up` 启动）；Redis 可选（不启动则自动降级到内存存储）。

---

## 4. 运行镜像（推荐命令）

在终端执行：

```bash
docker run -d \
  --name superbizagent \
  -p 9900:9900 \
  -e DASHSCOPE_API_KEY=sk-你的真实key \
  -e MILVUS_HOST=host.docker.internal \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal \
  ghcr.io/hitandrunaway/oncall-diagnosis-agent:latest
```

说明：

- `-d`：后台运行；`--name superbizagent`：容器命名，方便后续管理。
- `-p 9900:9900`：把容器的 9900 端口映射到宿主机 9900。
- `-e DASHSCOPE_API_KEY=...`：**运行时注入密钥**，替换成你的真实 key。
- `-e MILVUS_HOST=host.docker.internal`：关键——容器里的 `localhost` 是容器自己，不是宿主机；`host.docker.internal` 才能访问宿主机上（`make up` 启动的）Milvus。
- `-e SPRING_DATA_REDIS_HOST=host.docker.internal`：同理；Redis 未启动时此配置可省略。

> **Linux 宿主机**没有 `host.docker.internal`，改用 `--network host` 并去掉 `-p`，此时容器直接用宿主机的 `localhost` 访问 Milvus/Redis。

---

## 5. 验证

```bash
# 查看容器是否在运行
docker ps

# 健康检查（Milvus 连通性）
curl http://localhost:9900/milvus/health

# 查看日志
docker logs -f superbizagent
```

看到 `/milvus/health` 返回正常即代表服务已就绪，可访问前端 `http://localhost:9900`。

---

## 6. 常用环境变量

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `DASHSCOPE_API_KEY` | 占位符 | **必需**，DashScope 对话/向量化密钥 |
| `MILVUS_HOST` | `localhost` | Milvus 主机（容器内需 `host.docker.internal`） |
| `MILVUS_PORT` | `19530` | Milvus gRPC 端口 |
| `MILVUS_USERNAME` / `MILVUS_PASSWORD` | 空 | Milvus 认证（默认无） |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis 主机（可选，失败自动降级内存） |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis 端口 |
| `PROMETHEUS_BASE_URL` | `http://localhost:9090` | Prometheus 地址（AIOps 用） |

> 命名规则：Spring Boot 会把配置 `milvus.host` 映射到环境变量 `MILVUS_HOST`（点号变大写、短横线变下划线），其余同理。

---

## 7. 本地手动构建镜像（可选，一般不需要）

想不依赖 CI、自己在本地构建：

```bash
cd SuperBizAgent-release-2026-05-17
docker build -t superbizagent:local .
docker run -d -p 9900:9900 -e DASHSCOPE_API_KEY=sk-xxx \
  -e MILVUS_HOST=host.docker.internal superbizagent:local
```

构建分两阶段：先 `maven` 镜像编译打包，再把 fat jar 复制进精简的 `jre` 镜像，最终镜像不含源码、不含 Maven。

---

## 8. 手动推送镜像到 GitHub（仅在 CI 失效时才需要）

正常情况下 CI 已自动推送。若确需手动推送（例如打自定义标签），流程如下：

```bash
# 1. 本地登录 GHCR（用 GitHub 用户名 + Personal Access Token，需 packages:write 权限）
echo <YOUR_GITHUB_PAT> | docker login ghcr.io -u <你的GitHub用户名> --password-stdin

# 2. 给本地镜像打上 GHCR 标签
docker tag superbizagent:local ghcr.io/hitandrunaway/oncall-diagnosis-agent:v1.0.0

# 3. 推送
docker push ghcr.io/hitandrunaway/oncall-diagnosis-agent:v1.0.0
```

> Personal Access Token 在 GitHub → Settings → Developer settings → Personal access tokens 生成，勾选 `write:packages`。**不要**把 token 写进任何文件或提交到仓库。

---

## 9. 常见问题排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 启动后 `/milvus/health` 不通 | 容器内 `localhost` 指向容器自身 | 改用 `host.docker.internal`（Linux 用 `--network host`） |
| 日志报 Milvus 连接失败 | Milvus 未启动 | 宿主机先 `make up` 启动 Milvus，再运行容器 |
| 对话/向量化报鉴权错误 | `DASHSCOPE_API_KEY` 缺失或错误 | 检查 `-e` 是否传了正确 key；`docker inspect superbizagent` 看环境变量 |
| 端口被占用 | 9900 已被占用 | 改映射 `-p 9901:9900`，访问 `http://localhost:9901` |
| 启动卡住 / MCP 相关报错 | `application.yml` 中腾讯 CLS 的 MCP SSE 端点仍是占位符 | 按需在配置中关闭 `spring.ai.mcp.client.enabled` 或填入真实端点 |
| `docker ps` 看不到容器 | 容器启动即退出 | `docker logs superbizagent` 看启动错误 |

---

## 10. 更新与回滚

```bash
# 拉取最新镜像
docker pull ghcr.io/hitandrunaway/oncall-diagnosis-agent:latest

# 更新：停旧容器 → 删旧容器 → 用新镜像重跑
docker stop superbizagent && docker rm superbizagent
docker run -d --name superbizagent -p 9900:9900 \
  -e DASHSCOPE_API_KEY=sk-xxx \
  -e MILVUS_HOST=host.docker.internal \
  ghcr.io/hitandrunaway/oncall-diagnosis-agent:latest

# 回滚到某个历史版本（用 commit-sha 标签）
docker run -d --name superbizagent -p 9900:9900 \
  -e DASHSCOPE_API_KEY=sk-xxx \
  -e MILVUS_HOST=host.docker.internal \
  ghcr.io/hitandrunaway/oncall-diagnosis-agent:<commit-sha>
```
