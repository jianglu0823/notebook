# Spec: C3 日程事件 CRUD

提供日程事件的完整 CRUD 能力，是所有上层功能（排期/打卡/iCal/同步）的基础。

## ADDED Requirements

### Requirement: 事件创建

`POST /api/schedule/v1/events` MUST 接受 JSON body，字段含 `title` (1~100 字符) / `description` (≤1000 字符, 可选) / `startTime` (ISO8601) / `endTime` (ISO8601, 必须 > startTime) / `priority` (枚举 P0/P1/P2, 默认 P2) / `location` (≤200 字符, 可选) / `attendees` (字符串数组, 0~20 项) / `recurrence` (RFC 5545 RRULE 字符串, 可选) / `emoji` (单个 emoji 字符, 可选) / `idempotencyKey` (≤128 字符, 可选)。任一字段越界或缺必填 MUST 返回 400。成功 MUST 返回 201 + `eventId` (UUID) + `syncStatus="PENDING"`。

#### Scenario: 完整字段创建
- WHEN POST 完整字段 `{title:"周会", startTime:"2026-08-07T14:00:00+08:00", endTime:"2026-08-07T15:00:00+08:00", priority:"P1", location:"会议室A", attendees:["alice","bob"]}`
- THEN 201 + `eventId`；MySQL `schedule_event` 新增一行，title/description 加密落库

#### Scenario: title 越界
- WHEN title 长度 101
- THEN 400 + `error.field="title"`, `error.message="title 长度需 1~100"`

#### Scenario: endTime ≤ startTime
- WHEN `endTime` 早于或等于 `startTime`
- THEN 400 + `error.message="endTime 必须晚于 startTime"`

#### Scenario: 必填字段缺失
- WHEN 缺 `startTime`
- THEN 400 + `error.message="startTime 必填"`

### Requirement: 事件查询

`GET /api/schedule/v1/events` MUST 支持 `from` / `to` (ISO8601) / `priority` (可选) / `keyword` (可选) 查询参数，返回 `from <= event.startTime < to` 范围内的事件列表（按 `startTime ASC`）。`GET /api/schedule/v1/events/{id}` MUST 返回单事件详情。任一不存在的 `id` MUST 返回 404。

#### Scenario: 时间范围查询
- WHEN GET `?from=2026-08-01T00:00:00+08:00&to=2026-08-08T00:00:00+08:00`
- THEN 返回该周内全部事件数组，按 `startTime ASC` 排序

#### Scenario: 关键词模糊查询
- WHEN GET `?keyword=周会`
- THEN 返回 title/description 包含"周会"的事件（title/description 字段内存解密后做 LIKE）

#### Scenario: 不存在 ID
- WHEN GET `/api/schedule/v1/events/00000000-0000-0000-0000-000000000000`
- THEN 404 + `error.message="事件不存在"`

### Requirement: 事件更新

`PATCH /api/schedule/v1/events/{id}` MUST 支持部分字段更新（任意字段可独立改）。`priority` 更新 MUST 触发审计日志（`schedule_audit_log`）。`startTime` / `endTime` 修改后 MUST 重新跑冲突检测，若与已有事件冲突 MUST 返回 409 + 冲突原因。

#### Scenario: 修改标题
- WHEN PATCH `{title:"团队周会"}`
- THEN 仅 `title` 更新，`updatedAt=now`；返回 200

#### Scenario: 修改时间触发冲突
- WHEN PATCH `startTime` 至已有事件时间窗口
- THEN 返回 409 + `error.code="CONFLICT"`、`error.message="该时段已有『复盘』"`

### Requirement: 事件删除（软删）

`DELETE /api/schedule/v1/events/{id}` MUST 软删除（设 `deletedAt=now` + `status='deleted'`），不物理删除。删除后 MUST 从 `schedule_focus_session` / `schedule_checkin` 关联表软标记 `hidden=true`。已删除事件后续查询 MUST 过滤。

#### Scenario: 软删成功
- WHEN DELETE
- THEN 204；事件后续 GET 列表不返回；GET 详情返回 404

#### Scenario: 删除不存在 ID
- WHEN DELETE 不存在 ID
- THEN 404

### Requirement: 重复事件（RRULE）

`recurrence` 字段 MUST 接受 RFC 5545 RRULE 字符串（如 `FREQ=WEEKLY;BYDAY=MO,WE,FR`）。创建重复事件时 MUST 在 `schedule_event` 表存"主事件 + RRULE"，同时在 `schedule_event_occurrence` 物化未来 90 天的具体实例。

#### Scenario: 每周一三五重复
- WHEN POST `{recurrence:"FREQ=WEEKLY;BYDAY=MO,WE,FR", startTime:"2026-08-04T10:00:00+08:00", endTime:"2026-08-04T11:00:00+08:00"}`
- THEN 主事件入库；同时 `schedule_event_occurrence` 入库未来 90 天内所有周一三五的实例

#### Scenario: 非法 RRULE
- WHEN POST `recurrence:"INVALID"`
- THEN 400 + `error.message="RRULE 解析失败"`

### Requirement: 字段加密

`title` / `description` / `location` MUST 在写入 MySQL 前用 AES-256-GCM 加密（`schedule_event.title_cipher` / `description_cipher` / `location_cipher`，类型 `VARBINARY(1024)`）。查询时内存解密返回。密钥 32 字节 Base64 编码从 `SCHEDULE_AGENT_ENC_KEY` 环境变量读取。密钥未配置时 MUST 启动失败（fail-fast）。

#### Scenario: 加密落库
- WHEN 创建一个事件
- THEN `SELECT title_cipher FROM schedule_event WHERE id=?` 返回二进制密文（不可读中文）

#### Scenario: 解密查询
- WHEN 查询事件详情
- THEN 响应体中 `title` / `description` / `location` 是解密后的明文

#### Scenario: 密钥缺失
- WHEN `SCHEDULE_AGENT_ENC_KEY` 环境变量未设置
- THEN 启动期 `IllegalStateException("Encryption key required")`，应用退出
