# Spec: C7 iCal (.ics) 导出

将日程事件导出为标准 iCalendar 格式，兼容 Apple Calendar / Google Calendar / Outlook / 飞书日历。

## ADDED Requirements

### Requirement: 单事件导出

`GET /api/schedule/v1/events/{id}/ical` MUST 返回 `Content-Type: text/calendar; charset=utf-8`，body 为标准 iCalendar 2.0 格式（含 `BEGIN:VCALENDAR` / `END:VCALENDAR` / `VERSION:2.0` / `PRODID` / `BEGIN:VEVENT` / `END:VEVENT`）。VEVENT MUST 含 `UID` / `DTSTAMP` / `DTSTART` / `DTEND` / `SUMMARY` / `DESCRIPTION` (可选) / `LOCATION` (可选) / `PRIORITY` (P0=1/P1=5/P2=9) / `VALARM` 块。

#### Scenario: 完整单事件导出
- WHEN GET 单事件 ical
- THEN 响应是 VCALENDAR，含 VEVENT 块含 7 字段 + VALARM 块（15 分钟前提醒 + 事件开始时提醒）

#### Scenario: 不存在事件
- WHEN GET 不存在 ID
- THEN 404

### Requirement: 范围导出

`GET /api/schedule/v1/events/ical?from=2026-08-01T00:00:00%2B08:00&to=2026-08-31T23:59:59%2B08:00` MUST 返回范围内所有事件的单个 VCALENDAR（含多个 VEVENT）。`from` 缺省默认今天；`to` 缺省默认 `from + 30 天`；范围跨度 MUST 不超过 90 天。

#### Scenario: 一周范围
- WHEN GET `?from=2026-08-01&to=2026-08-08`
- THEN 单个 VCALENDAR 含该周所有事件

#### Scenario: 范围超限
- WHEN `to - from > 90 天`
- THEN 400 + `error.message="范围跨度不能超过 90 天"`

#### Scenario: 缺省范围
- WHEN GET 不带 from/to
- THEN 默认今天到 +30 天

### Requirement: 字符编码

VEVENT 内容 MUST 使用 UTF-8 编码。中文字段 MUST 正确编码（`SUMMARY:团队周会` 直接可读，不转义）。`Content-Type: text/calendar; charset=utf-8` 头必须出现。

#### Scenario: 中文标题
- WHEN title 是"团队周会"
- THEN VCALENDAR 中 `SUMMARY:团队周会`（UTF-8 原文），不是 `\u` 转义

### Requirement: VALARM 提醒

每个 VEVENT MUST 含至少 1 个 VALARM，默认 15 分钟前提醒（`TRIGGER:-PT15M`）。可选 `?reminder=30` query 参数覆盖为 30 分钟前。多个提醒用 `?reminder=15,5,0` 逗号分隔，最多 5 个。

#### Scenario: 默认 15 分钟前
- WHEN GET 单事件 ical 无 query
- THEN VALARM 含 `TRIGGER:-PT15M`

#### Scenario: 自定义提醒
- WHEN GET `?reminder=30,5`
- THEN VALARM 含 2 个：30 分钟前 + 5 分钟前

### Requirement: 重复事件导出

重复事件（`recurrence` 字段非空）MUST 用 `RRULE` 字段在 VEVENT 中表示，而不是展开成多条 VEVENT。范围导出的"实例展开"由客户端（Apple Calendar 等）根据 RRULE 自行计算。

#### Scenario: 每周一三五
- WHEN 事件 `recurrence="FREQ=WEEKLY;BYDAY=MO,WE,FR"`
- THEN VEVENT 含 `RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR`，不展开

### Requirement: Content-Disposition

响应 MUST 含 `Content-Disposition: attachment; filename="schedule-{eventId}.ics"` (单事件) 或 `Content-Disposition: attachment; filename="schedule-{from}-{to}.ics"` (范围)，浏览器触发下载。

#### Scenario: 单事件文件名
- WHEN GET 单事件 ical
- THEN header `Content-Disposition: attachment; filename="schedule-{id}.ics"`

#### Scenario: 范围文件名
- WHEN GET 范围 ical
- THEN header `Content-Disposition: attachment; filename="schedule-20260801-20260831.ics"`
