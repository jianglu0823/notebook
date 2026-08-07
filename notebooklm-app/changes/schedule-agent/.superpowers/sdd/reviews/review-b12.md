# B12 Review: Dockerfile + docker-compose + 部署 README

**Wave**: B12-docker-docs
**Branch**: schedule-agent-build
**Base**: 628d5e7
**Head**: 1eef702
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B12 要求产出:
- [x] 多阶段 Dockerfile (Maven build → JRE 21 runtime)
- [x] docker-compose.yml (MySQL 8.0 + schedule-agent 一键起)
- [x] README.md 更新(env vars 表 + Docker 部署 + SDK 接入 + 指标 + 测试覆盖)
- [x] /actuator/health 健康检查可达
- [x] SCHEDULE_AGENT_ENC_KEY 缺失 fail-fast(docker-compose 显式声明)

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c13 R 部署 | 多阶段 Docker build | `Dockerfile` 阶段1 build + 阶段2 runtime |
| c13 R 端口 | EXPOSE 8081 | `EXPOSE 8081` |
| c13 R 健康检查 | /actuator/health 探针 | `HEALTHCHECK` + 30s 间隔 |
| c13 R 密钥注入 | SCHEDULE_AGENT_ENC_KEY env | docker-compose `${SCHEDULE_AGENT_ENC_KEY:?...}` |
| c13 R 内部 token | SCHEDULE_AGENT_INTERNAL_TOKEN env | docker-compose `${SCHEDULE_AGENT_INTERNAL_TOKEN:-...}` |
| c13 R 文档 | 部署步骤可复制 | README 给出 openssl + docker compose + curl 三步 |

## 测试证据

本批次为 chore/docs,无新单测。
回归基线沿用 B11 末态:
- schedule-agent-contract: 15 ✓
- schedule-agent-server: 229 ✓
- schedule-agent-client-sdk: 13 ✓
- 集成测试 (B10): 10 ✓

合计 267 单元 + 10 集成,全绿。

## 关键工程决策

1. **多阶段 build 镜像体积**: 阶段1 用 `eclipse-temurin:21-jdk` (完整 JDK + Maven),阶段2 用 `eclipse-temurin:21-jre` (只 JRE)。fat jar 复制为 `app.jar`,镜像中无源码、无 .m2 缓存。
2. **HEALTHCHECK 留 60s start-period**: Spring Boot 启动 + Flyway migration + 6 个 skill 注册需要时间,start-period 太短会被误判 unhealthy。
3. **docker-compose 强制 ENC_KEY**: `${SCHEDULE_AGENT_ENC_KEY:?...}` 让 docker compose 启动时直接报错并退出,避免运行时因缺密钥崩服。
4. **MySQL 端口 3307**: 与 notebooklm-app 的 3306 错开,本地可同时跑两个 MySQL。
5. **internal token 默认值**: `dev-internal-token-change-me` 仅为本地 dev,生产通过环境变量覆盖;README 明确警告。
6. **/actuator/prometheus IP 白名单**: README 注明 127/192.168/10,公网 403。
7. **没有覆盖 ENTRYPOINT 用 exec form**: 用 `["sh", "-c", "exec java $JAVA_OPTS -jar ..."]` 是为了 JAVA_OPTS 展开,生产改用 `["java", "-jar", ...]` 也是合法。

## 已知后续

- 镜像基于 temurin 官方镜像,未做 rootless(后续可换 distroless)。
- docker-compose 未覆盖 Nacos/Lark 集成路径(仅 MOCK/IN-MEMORY 默认值),需要更完整的环境变量时手动覆盖。
- README 还没补 "性能基准" 数据,后续 release 阶段补齐。
- multi-arch build (linux/amd64 + arm64) 未启用,生产部署到 ARM 实例前需补 buildx 配置。
