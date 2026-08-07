# Spec: C8 专注计时器 + 打卡

事件开始时联动启动"专注计时器"，任务完成后用户手动确认打卡，生成连续打卡统计。

## ADDED Requirements

### Requirement: 启动专注会话

`POST /api/schedule/v1/events/{id}/focus/start` MUST 接受空 body 或 `{plannedDurationMinutes}`（可选，默认 = 事件 endTime - startTime）。成功 MUST 返回 201 + `sessionId` (UUID) + `startedAt`。已存在未结束的 `focusSession`（同一 eventId）时 MUST 复用，返回原 sessionId，不创建新行。

#### Scenario: 启动新会话
- WHEN POST `/focus/start`
- THEN 201 + sessionId；`schedule_focus_session` 新增一行，`status="RUNNING"`、`startedAt=now`

#### Scenario: 复用已有会话
- WHEN 同一 eventId 已有 RUNNING 会话
- THEN 201 + 返回原 sessionId；不创建新行

#### Scenario: 事件已结束
- WHEN 当前时间 > event.endTime
- THEN 400 + `error.message="事件已结束，无法启动专注"`

### Requirement: 心跳保活

`POST /api/schedule/v1/focus/{sessionId}/heartbeat` MUST 由客户端每 60s 调用一次。服务端更新 `lastHeartbeatAt`。**超过 5 分钟无心跳** MUST 自动标记 `status="ABANDONED"`、`abandonedAt=now`。该判定由后台 scheduled task 每 1 分钟扫描一次（不等下一次心跳）。

#### Scenario: 正常心跳
- WHEN 心跳间隔 < 5 分钟
- THEN `lastHeartbeatAt=now`；`status` 保持 `RUNNING`

#### Scenario: 心跳超时
- WHEN 上次心跳距 now > 5 分钟
- THEN 后台 task 标记 `status="ABANDONED"`；下次心跳返回 410 GONE + `error.message="会话已超时，请重新启动"`

#### Scenario: 抗作弊
- WHEN 客户端尝试伪造 `lastHeartbeatAt`
- THEN 服务端忽略请求 body 中的时间字段，仅以服务端时钟为准

### Requirement: 完成打卡

`POST /api/schedule/v1/focus/{sessionId}/complete` MUST 接受空 body 或 `{reflection: "string"}` (≤500 字符, 可选)。成功 MUST 标记 `status="COMPLETED"`、`completedAt=now`、记录 `actualDurationMinutes`。同时 MUST 在 `schedule_checkin` 表新增打卡记录：`userId` / `eventId` / `checkinDate=today` / `streakDays`。

#### Scenario: 正常完成
- WHEN POST `/complete`
- THEN 200；`status="COMPLETED"`；`schedule_checkin` 新增一行；返回当前 `streakDays`

#### Scenario: 已 ABANDONED 会话
- WHEN 完成的会话已是 ABANDONED 状态
- THEN 400 + `error.message="会话已废弃，无法打卡"`

### Requirement: 连续打卡统计

`schedule_checkin` 表 MUST 按 `(userId, eventId, checkinDate)` 唯一索引。`streakDays` 计算 MUST 满足：相同 eventId 在过去 N 天内连续有 `checkinDate` 记录时，N+1 递增；中断 1 天即归零。

#### Scenario: 连续 7 天
- WHEN eventId X 在 8 月 1~7 日每天都有 checkin
- THEN 8 月 7 日完成时 `streakDays=7`；触发 milestone 提示"已连续打卡 7 天！"

#### Scenario: 中断归零
- WHEN 8 月 1~5 日打卡，6 日中断，7 日再打卡
- THEN 7 日 `streakDays=1`（不延续 5）

#### Scenario: 同一事件多次打卡去重
- WHEN 同一 eventId 当天已有 checkin，再调 `/complete`
- THEN 同一 checkinDate 行 `count+1`，不新增行

### Requirement: Milestone 触发

`streakDays` 达到 7 / 30 / 100 / 365 时 MUST 触发 milestone 事件，存入 `schedule_audit_log`（`action="CHECKIN_MILESTONE"`，附 `milestoneDays` 字段）。前端可通过 SSE 订阅 `topic=focus-milestone` 接收。

#### Scenario: 7 天 milestone
- WHEN streakDays 首次达到 7
- THEN audit log 新增 `CHECKIN_MILESTONE/7`；SSE topic 推送 `{"milestone":7,"message":"已连续打卡 7 天！"}`

#### Scenario: 重复 milestone 去重
- WHEN 同一 milestone 天数再次达到（如 8 天后再 30 天）
- THEN 仅 30 天触发，7 天不重复触发

### Requirement: 抗打扰

进行中（`status="RUNNING"`）的 focus 会话 MUST 屏蔽同一 eventId 的"晚到 N 分钟提醒"，避免与专注状态冲突。心跳 + 完成路径不受影响。

#### Scenario: 屏蔽迟到提醒
- WHEN focus 会话 RUNNING + 系统原计划在 event.startTime 推"事件开始"提醒
- THEN 跳过该推送；`schedule_audit_log` 记录 `REMINDER_SUPPRESSED_BY_FOCUS`
