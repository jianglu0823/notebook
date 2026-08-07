# Spec: C10 外部日历同步钩子

抽象外部日历（飞书/Google/Apple）同步接口，提供 Mock 默认实现 + 占位适配器。本期不实现真实 OAuth 流程。

## ADDED Requirements

### Requirement: 适配器接口契约

`io.scheduleagent.adapter.ExternalCalendarAdapter` 接口 MUST 定义 4 个方法签名：

```java
String create(ScheduleEventDto event);
boolean update(String externalId, ScheduleEventDto event);
boolean delete(String externalId);
List<ExternalEventDto> list(Date from, Date to);
```

所有实现 MUST 实现这 4 个方法 + `String getProviderName()` (返回 `"mock"` / `"lark"` / `"google"` 等)。

#### Scenario: 接口方法签名锁定
- WHEN 编译 `schedule-agent-server`
- THEN `MockExternalCalendarAdapter` / `LarkCalendarAdapter` / `GoogleCalendarAdapter` 均通过编译，方法签名与接口一致

### Requirement: Mock 适配器（默认）

`MockExternalCalendarAdapter` MUST 写 INFO 日志（含 `providerName + eventId + action`）+ 落 `~/.schedule-agent/external-mock.json`（追加 JSON Lines 格式）。`create` 返回 `"mock-<uuid>"`，`update`/`delete` 返回 `true`，`list` 返回最近 100 条历史。

#### Scenario: Mock create
- WHEN 调 `create(event)`
- THEN 日志输出 `INFO: [MockExternalCalendar] create eventId={id} title={title}`
- AND `external-mock.json` 追加 1 行 JSON
- AND 返回 `mock-<uuid>`

#### Scenario: Mock list
- WHEN 调 `list(from, to)`
- THEN 返回最近 100 条 mock 记录，按 createdAt DESC

### Requirement: 飞书/Google 占位实现

`LarkCalendarAdapter` / `GoogleCalendarAdapter` MUST 仅含 `TODO` 注释 + 抛 `UnsupportedOperationException("Lark OAuth flow not implemented in v1")` / `UnsupportedOperationException("Google OAuth flow not implemented in v1")`。两类适配器 MUST 在 `application.yml` 中通过 `schedule-agent.adapter.provider=lark|google|mock` 切换；`mock` 是默认。

#### Scenario: 占位实现抛异常
- WHEN 调 `LarkCalendarAdapter.create(...)`
- THEN 抛 `UnsupportedOperationException`，异常 message 含 `"Lark OAuth"`

#### Scenario: 配置切换
- WHEN `schedule-agent.adapter.provider=mock`
- THEN `MockExternalCalendarAdapter` 是 Spring 容器中的 Bean，`LarkCalendarAdapter` 不被注入

### Requirement: 异步同步

外部同步 MUST 走 `@Async` + 独立线程池（`schedule-agent.adapter.executor.corePoolSize=2, maxPoolSize=4`），不得阻塞主请求线程。同步失败 MUST 写 `schedule_audit_log`（`action="EXTERNAL_SYNC_FAILED"`），不返回 5xx 给客户端。

#### Scenario: 异步执行
- WHEN 主线程调 `externalAdapter.create(event)`
- THEN 立即返回 202 Accepted；后台线程池异步执行

#### Scenario: 失败不阻塞
- WHEN 异步执行抛异常
- THEN 主线程早已返回 202；audit log 记录失败；`schedule_event.syncStatus="FAILED"`

### Requirement: 同步结果查询

`GET /api/schedule/v1/external-sync/{eventId}` MUST 返回 `{provider, externalId, lastSyncAt, syncStatus, lastError}`，含最近一次同步尝试详情。

#### Scenario: 同步成功查询
- WHEN Mock 适配器已成功 create
- THEN GET 返回 `{provider:"mock", externalId:"mock-uuid", lastSyncAt:"...", syncStatus:"SYNCED", lastError:null}`

#### Scenario: 同步失败查询
- WHEN Lark 适配器抛异常
- THEN GET 返回 `syncStatus:"FAILED"`, `lastError:"Lark OAuth flow not implemented in v1"`

### Requirement: 重试策略

`syncStatus="FAILED"` 事件 MUST 在下次手动触发（`POST /api/schedule/v1/events/{id}/sync-retry`）时重试。重试最多 3 次；3 次后保持 FAILED 不再自动重试。

#### Scenario: 手动重试
- WHEN POST `/sync-retry`
- THEN 异步重试 1 次；audit log 记录 `EXTERNAL_SYNC_RETRY`

#### Scenario: 重试耗尽
- WHEN 已重试 3 次仍失败
- THEN 后续 `/sync-retry` 返回 400 + `error.message="已达最大重试次数（3）"`
