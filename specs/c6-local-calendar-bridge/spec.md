# Spec: C6 本地日历桥（Local Calendar Bridge）

`schedule-agent` 与 Mac 本地日历（Calendar.app）的桥接层，支持 CREATE / UPDATE / DELETE 三种操作的真实或 Mock 模式。

## ADDED Requirements

### Requirement: 桥接模式配置

`schedule-agent.calendar.mode` 配置项 MUST 接受 3 个值：`mock`（默认，写日志 + 落 iCal 文件）/ `mac-local`（启用 AppleScript）/ `disabled`（完全关闭，所有写操作返回 501 NOT_IMPLEMENTED）。应用启动时 MUST 校验配置项合法值；非法值返回启动失败。

#### Scenario: 默认 mock 模式
- WHEN 未配置 `schedule-agent.calendar.mode`
- THEN 默认 `mock`；启动日志 `[LocalCalendarBridge] mode=mock`

#### Scenario: mac-local 模式
- WHEN 配置 `mode=mac-local`
- THEN 启动期检测 `osascript` 命令可用性；不可用返回启动失败

#### Scenario: 非法配置
- WHEN `mode=foo`
- THEN 启动失败 + `IllegalArgumentException("Unknown calendar mode: foo")`

### Requirement: CREATE 操作

`POST /api/schedule/v1/internal/local-calendar/sync` MUST 接受 body `{action:"CREATE", event:{id,title,startTime,endTime,location,priority}}`：
- mock 模式：写日志 + 追加到 `~/.schedule-agent/calendar.ics`（用 biweekly 库生成 VEVENT）
- mac-local 模式：调 `osascript -e 'tell application "Calendar" to ...'`
- disabled 模式：返回 501

成功 MUST 返回 200 + `localEventId`（mock 模式下是 VEVENT 的 UID；mac-local 下是 Calendar.app 的 event id）。失败 MUST 返回 500 + 错误码 `LOCAL_CALENDAR_FAILED`。

#### Scenario: mock 模式 CREATE
- WHEN POST CREATE + 1 个 event
- THEN 200 + `localEventId="<UUID>@schedule-agent.mock"`；`~/.schedule-agent/calendar.ics` 新增 VEVENT

#### Scenario: mac-local 模式 CREATE
- WHEN POST CREATE
- THEN 调 `osascript`，输出包含 event id；返回 200 + `localEventId`

#### Scenario: AppleScript 权限拒绝
- WHEN mac-local 模式但用户未授权"自动化权限"
- THEN 捕获 `osascript` 退出码 1 + stderr 含"Not authorized"
- AND 返回 403 + `error.code="PERMISSION_DENIED"`、`error.message="请在系统设置→隐私与安全性→自动化中允许 schedule-agent 控制 Calendar.app"`

### Requirement: UPDATE 操作

`POST` body `{action:"UPDATE", event:{...}}` MUST 根据 `event.id` 查找本地日历中对应 VEVENT（mock 模式通过 UID 匹配；mac-local 模式通过 Calendar.app id），更新其字段。找不到对应 VEVENT MUST 返回 404。

#### Scenario: mock 模式 UPDATE
- WHEN POST UPDATE 携带原 event.id
- THEN 找到对应 VEVENT，更新 SUMMARY/DTSTART/DTEND；返回 200

#### Scenario: mac-local UPDATE
- WHEN mac-local 模式 UPDATE
- THEN 调 `osascript` 更新 Calendar.app 事件；返回 200

#### Scenario: 原事件不存在
- WHEN UPDATE 不存在 event.id
- THEN 404 + `error.message="本地日历中找不到该事件"`

### Requirement: DELETE 操作

`POST` body `{action:"DELETE", event:{id}}` MUST 删除本地日历对应事件。删除后 MUST 软标记 `schedule_event.syncStatus="DELETED"`。

#### Scenario: mock DELETE
- WHEN POST DELETE
- THEN 找到 VEVENT，标记 X-METHOD=DELETED 注释；返回 200；schedule_event.syncStatus="DELETED"

#### Scenario: mac-local DELETE
- WHEN mac-local DELETE
- THEN 调 `osascript` 删 Calendar.app 事件；返回 200

### Requirement: 幂等性

同一 `event.id` 重复 CREATE MUST 转为 UPDATE（去重）。`Idempotency-Key` header 24h 内 MUST 去重。

#### Scenario: 重复 CREATE 去重
- WHEN 同一 event.id CREATE 第二次
- THEN 自动转 UPDATE，不创建第二条

### Requirement: 同步状态

每个 `schedule_event` 行 MUST 有 `syncStatus` 字段（`PENDING` / `SYNCED` / `FAILED` / `DELETED`）。CREATE/UPDATE/DELETE 后 MUST 异步更新。FAILED MUST 记录 `lastSyncError` 字段。`GET /api/schedule/v1/events/{id}` MUST 返回 `syncStatus`。

#### Scenario: 同步失败标记
- WHEN AppleScript 返回非 0 退出码
- THEN `syncStatus="FAILED"`，`lastSyncError="<错误摘要>"`

#### Scenario: 重试机制
- WHEN `syncStatus="FAILED"`
- THEN 下次相同 event 操作时 MUST 重试；最多 3 次后保持 FAILED 不再重试
