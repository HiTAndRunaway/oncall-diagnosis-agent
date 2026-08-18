# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------
# 构建阶段：Maven 编译打包
# ---------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷贝 pom.xml 以复用依赖缓存层
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline || true

# 拷贝源码并打包（跳过测试，测试入 CI 为 P0-1 的后续项）
COPY src ./src
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------
# 运行阶段：仅保留 JRE + 可执行 jar
# ---------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 复制构建产物
COPY --from=build /build/target/super-biz-agent-1.0-SNAPSHOT.jar app.jar

# 暴露应用端口（与 application.yml 中 server.port 一致）
EXPOSE 9900

# 运行应用；DASHSCOPE_API_KEY 等敏感配置通过 -e 在运行时注入，绝不烘焙进镜像
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
