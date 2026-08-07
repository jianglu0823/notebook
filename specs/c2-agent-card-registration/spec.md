# Spec: C2 Agent Card 注册 + 服务发现

`schedule-agent` 启动时向注册中心注册自身 Agent Card 元数据，供伴侣 Agent 等其他 Client Agent 通过服务发现拉取。

## ADDED Requirements

### Requirement: 启动自动注册

应用启动 30s 内 MUST 完成 Agent Card 注册流程。注册中心地址从 `schedule-agent.registry.url` 配置项读取：值为 `in-memory` 时走本地内存注册表（dev 默认），值为 `nacos://<host>:<port>` 时走 Nacos HTTP API 注册（prod）。

#### Scenario: dev profile 内存注册
- WHEN 启动时 `schedule-agent.registry.url=in-memory`
- THEN `InMemoryAgentRegistry` Bean 被注入，启动日志输出 `[AgentRegistry] Registered to in-memory registry, agentCard=schedule-agent-v1`

#### Scenario: prod profile Nacos 注册
- WHEN 启动时 `schedule-agent.registry.url=nacos://nacos.internal:8848`
- THEN 调 Nacos `/nacos/v1/ns/instance` POST 接口，body 含 `serviceName=schedule-agent-v1`、`ip`、`port=8081`、`metadata.agentCard=<urlencoded JSON>`
- AND 启动失败（网络/认证错误）时应用 MUST 启动并降级为 in-memory（不阻塞），同时打 WARN 日志

### Requirement: Agent Card 心跳

注册后 MUST 每 15s 发送一次心跳（Nacos: `/nacos/v1/ns/instance/beat`）。心跳失败 3 次连续 MUST 触发重新注册流程。

#### Scenario: 心跳成功
- WHEN Nacos 返回 200
- THEN 日志 `agent registry heartbeat ok`

#### Scenario: 连续 3 次心跳失败
- WHEN Nacos 连续返回 500
- THEN 触发重新注册；重新注册成功后恢复心跳
- AND `schedule_metric_event` 表新增 `REGISTRY_RE_REGISTER` 类型事件

### Requirement: 服务发现查询

`GET /a2a/v1/agent-card/discover?skill=create_event` MUST 返回所有支持该 skill 的 Agent 列表（含本实例 + 其他 A2A Agent）。结果按响应延迟 P95 升序，限制最多 10 条。

#### Scenario: 查询 create_event skill
- WHEN 客户端 GET `?skill=create_event`
- THEN 返回 200，数组含本实例（`schedule-agent-v1`），每个元素含 `agentName` / `url` / `latencyMs` / `skills`

#### Scenario: 无匹配 skill
- WHEN 客户端查询不存在的 skill
- THEN 返回 200 + 空数组 `[]`，不返回 404

### Requirement: Agent Card 版本管理

`AgentCard.version` MUST 遵循语义化版本（`MAJOR.MINOR.PATCH`），版本变更 MUST 触发重新注册。`MAJOR` 变更 MUST 同时清空 in-memory 注册表（防止旧版被误发现）。

#### Scenario: MINOR 版本升级
- WHEN 启动时 `agent-card.version=1.1.0`（旧版 1.0.0 已在注册表）
- THEN 旧版实例被移除，新版注册

#### Scenario: MAJOR 版本升级
- WHEN 启动时 `agent-card.version=2.0.0`
- THEN in-memory 注册表被全清，仅保留 2.0.0 实例

### Requirement: 注册安全

Nacos 注册 MUST 携带 `username` / `password`（从 `schedule-agent.registry.username` / `password` 读取）。明文密码 MUST 走 `SCHEDULE_AGENT_NACOS_PASSWORD` 环境变量，禁止落配置文件。

#### Scenario: 密码缺失
- WHEN `schedule-agent.registry.url=nacos://...` 但 `username`/`password` 未配置
- THEN 启动期返回配置错误，应用退出
