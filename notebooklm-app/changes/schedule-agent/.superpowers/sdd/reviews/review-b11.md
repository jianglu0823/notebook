# B11 Review: client-sdk A2A/REST 客户端封装 + E2E

**Wave**: B11-sdk-e2e
**Branch**: schedule-agent-build
**Base**: e646d13
**Head**: 628d5e7
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B11 要求产出:
- [x] `A2AClient` getAgentCard / discover / send + X-Idempotency-Key 头
- [x] `ScheduleTool` createEvent / getEvent + X-User-Id 头
- [x] `AgentCardDiscoverer` loadOwn / findBySkill
- [x] E2E 测试覆盖所有端点(JDK 内置 HttpServer,避免 mockwebserver 离线依赖)
- [x] mvn -o test client-sdk BUILD SUCCESS

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c1 R 客户端封装 | A2AClient 调 /a2a/v1/tasks/send | `A2AClient.send(taskType, text)` |
| c2 R 客户端发现 | A2AClient.discover(skill) | `discover(skill)` GET /agent-card/discover |
| c3 R 客户端封装 | ScheduleTool.createEvent | `createEvent(request)` POST /events |
| c3 R userId 头 | X-User-Id 头 | `header("X-User-Id", userId)` |
| c3 R 幂等 | idempotencyKey | `client.send` 头 + body 双写 |
| c13 R 可被依赖 | 独立模块 schedule-agent-client-sdk | mvn dependency 即可被 companion-chat 引用 |

## 测试证据

```
mvn -o -pl schedule-agent-client-sdk -am test
schedule-agent-contract ............................ SUCCESS
schedule-agent-client-sdk .......................... SUCCESS
BUILD SUCCESS
```

B11 详细分布(13 个 E2E):
- A2AClientE2ETest: 7
  - getAgentCardParsesAllSkills / discoverReturnsList / sendReturnsJsonRpcResponse /
    sendWithIdempotencyKeyAddsHeader / sendFailureThrowsIoException /
    getAgentCardFailureThrowsIoException / discoverFailureThrowsIoException
- ScheduleToolE2ETest: 4
  - createEventPostsToCorrectPath / createEventIncludesIdempotencyKey /
    getEventReturnsBody / failureThrowsIoException
- AgentCardDiscovererE2ETest: 2
  - loadOwnReturnsAgentCard / findBySkillReturnsList

## 关键工程决策

1. **用 JDK 内置 HttpServer 替代 mockwebserver**:mockwebserver 4.12.0 在离线模式无法解析。`com.sun.net.httpserver.HttpServer` 是 JDK 自带,免依赖。`InProcessHttpServer` 用 ConcurrentHashMap 存 stub,简单够用。
2. **HttpServer 头大小写**:Java HttpServer 头全部 lowercase,测试断言统一 `.toLowerCase().contains("x-idempotency-key=[idem-123]")` 兼容。
3. **路径去掉 trailing slash**:`A2AClient` 构造时 `baseUrl.replaceAll("/$", "")` 保证拼接 `baseUrl + "/a2a/v1/..."` 不出 `//`。
4. **JSON-RPC 内部 params 用 LinkedHashMap**:taskType / input / idempotencyKey / taskId 顺序无关但可读性更好。
5. **OkHttpClient 默认 5s connect / 30s read**:覆盖 A2A 同步调用(预期 < 500ms)和长任务(30s 兜底)。
6. **ScheduleTool 自动注入 idempotencyKey**:createEvent 调用时 `UUID.randomUUID()` 生成,避免客户端重复 POST。

## 已知后续

- OkHttpClient 没有 connection pool 配置:后续可加 maxIdleConnections/keepAliveDuration。
- ScheduleTool 没补齐 updateEvent / deleteEvent:本期 v1 范围,后续 v2 补。
- 真实 HTTP E2E(连真服务跑)留给 release 阶段集成测试。
