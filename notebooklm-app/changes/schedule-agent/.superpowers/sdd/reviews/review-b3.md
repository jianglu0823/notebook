# B3 Review: 事件 CRUD + A2A 协议服务端 + 状态机 + 幂等键

**Wave**: B3-events-a2a
**Branch**: schedule-agent-build
**Base**: c3081f0
**Head**: 0f4d395
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B3 要求产出:
- [x] EventEntity JPA 软删 (is_active) + EventRepository
- [x] EventService create/get/patch/delete + FieldEncryptor 注入 + 跨用户 404
- [x] RecurrenceService RFC 5545 RRULE 解析 + 90 天物化
- [x] A2AController POST /a2a/v1/tasks/send 自然语言建日程
- [x] TaskDispatcher 6 个 SkillHandler 占位
- [x] SseTaskStreamer POST /a2a/v1/tasks/sendSubscribe + 30min TTL + 心跳
- [x] IdempotencyService Caffeine 24h 幂等键去重
- [x] ApiExceptionHandler 统一错误响应 (error.code + error.message)
- [x] EventController REST CRUD

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c3 R CRUD | create/get/patch/delete | EventService 4 方法 |
| c3 R 软删 | 删除设 is_active=false | EventService.delete |
| c3 R 跨用户 | 越权访问 404 | EventService.get 检查 userId |
| c3 R 冲突 | 改 startTime 触发冲突检测 | EventService.patch 滑动窗口 |
| c1 R 自然语言 | TextPart 走 NLU | SmartScheduleHandler 占位 |
| c1 R 结构化 | DataPart 跳过 NLU | dispatcher 按 part.kind 分发 |
| c1 R 状态机 | submitted→working→completed | TaskStatus.canTransitionTo |
| c1 R 幂等 | 24h 去重 | IdempotencyService + Caffeine |
| c1 R SSE | sendSubscribe 推流 | SseTaskStreamer 30min TTL |
| c1 R 错误格式 | error.code + error.message | ApiExceptionHandler |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B3 新增 27 测试:EventEntity 3 + EventService 8 + Recurrence 4 + A2AController 6 + SseTaskStreamer 3 + Idempotency 3。

## 关键工程决策

1. 软删 is_active + JPA @Where:查询自动过滤。
2. EventMapper DTO↔Entity:集中处理加密/解密。
3. 跨用户 404 而非 403:不暴露存在性。
4. 冲突检测滑动窗口 ±2h 内存比对。
5. A2A state machine 在 dispatcher 集中。
6. IdempotencyService Caffeine expireAfterWrite 24h。
7. SSE 30min TTL + 15s 心跳保活。

## 已知后续

- RecurrenceService 物化 90 天上限,跨年事件需客户端循环拉。
- SseTaskStreamer 单机内存,集群需共享 (Redis v2)。
