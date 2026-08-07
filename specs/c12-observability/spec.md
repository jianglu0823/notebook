# Spec: C12 埋点指标

通过 Micrometer + Prometheus 暴露关键业务指标，对应 PRD 第五章"数据埋点与评估指标"。

## ADDED Requirements

### Requirement: A2A 通信延迟指标

`schedule.a2a.request.duration` MUST 是 Micrometer Timer，记录每次 `tasks/send` / `tasks/sendSubscribe` 调用的端到端延迟（毫秒）。Tag 含 `taskType` / `status` (completed/failed/input-required)。`/actuator/prometheus` MUST 暴露 `schedule_a2a_request_duration_seconds_*` 系列。

#### Scenario: 正常调用
- WHEN POST `tasks/send`，taskType=create_event
- THEN timer 记录 1 条样本，tag `taskType="create_event"`, `status="completed"`

#### Scenario: P95 暴露
- WHEN Prometheus 抓取 `/actuator/prometheus`
- THEN 含 `schedule_a2a_request_duration_seconds{quantile="0.95",...}` 指标

#### Scenario: PRD 目标 < 500ms
- WHEN 1000 次调用 P95 统计
- THEN P95 < 500ms（PRD 要求）

### Requirement: 智能日程周活指标

`schedule.event.weekly_active_users` MUST 是 Micrometer Gauge，每个周一 00:00 统计过去 7 天有创建/查询/打卡行为的独立 userId 数。Prometheus 抓取后用 `increase(schedule_event_weekly_active_users[7d])` 算趋势。

#### Scenario: 周一刷新
- WHEN 跨过周一 00:00
- THEN Gauge 重新计算过去 7 天活跃用户数

#### Scenario: PRD 目标 ≥ 30%
- WHEN 抓取 `schedule_event_weekly_active_users`
- THEN 总用户基数从 `schedule_user_pref` 统计；周活 / 总用户 ≥ 30%（PRD 要求）

### Requirement: 对话建日程轮次指标

`schedule.chat.create.turns` MUST 是 Distribution Summary，记录每次 `tasks/send` + 后续 input-required 续传的轮次。Tag 含 `userId`（可选，hash 化）。Prometheus 暴露 `schedule_chat_create_turns_*`。

#### Scenario: 一次成功
- WHEN 用户 1 轮 `tasks/send` 就成功
- THEN 记录 1 次样本，value=1

#### Scenario: 一次冲突一次续传
- WHEN 用户 1 轮冲突 + 1 轮续传 = 2 轮
- THEN 记录 value=2

#### Scenario: PRD 目标 ≤ 2 轮
- WHEN P50 计算
- THEN P50 ≤ 2（PRD 要求）

### Requirement: 日程确认率指标

`schedule.event.confirm` MUST 是 Counter，含 `confirm=true|false` tag。AI 推荐 → 用户确认转化率 = `sum(rate(confirm{confirm="true"}[7d])) / sum(rate(confirm[7d]))`。

#### Scenario: 确认成功
- WHEN 用户确认 AI 推荐的时段（调 `tasks/send` 携带原 taskId + 用户选择）
- THEN counter `confirm=true` +1

#### Scenario: 拒绝推荐
- WHEN 用户放弃推荐（超时未续传）
- THEN counter `confirm=false` +1（由后台扫描任务统计）

#### Scenario: PRD 目标 ≥ 75%
- WHEN 抓取 7 天 ratio
- THEN ratio ≥ 0.75（PRD 要求）

### Requirement: 打卡完成率指标

`schedule.checkin.completed` MUST 是 Counter，含 `priority` tag。`schedule_checkin_event_total` 统计应打卡次数（事件到点后无 ABANDONED 即"应打卡"）。完成率 = `sum(rate(completed[7d])) / sum(rate(event_total[7d]))`。

#### Scenario: 完成打卡
- WHEN `focus/complete` 调成功
- THEN counter `schedule_checkin_completed{priority="P1"}` +1

#### Scenario: 应打卡统计
- WHEN 事件 endTime 过后 30 分钟
- THEN 后台 task 累计 `schedule_checkin_event_total{priority=...}` +1（如未 ABANDONED）

#### Scenario: PRD 目标 ≥ 60%
- WHEN 抓取 7 天 ratio
- THEN ratio ≥ 0.60（PRD 要求）

### Requirement: Prometheus 端点

`/actuator/prometheus` MUST 暴露给 `127.0.0.1` + 内网（`192.168.0.0/16` + `10.0.0.0/8`），公网访问 MUST 返回 403。健康检查 `/actuator/health` MUST 公开（不限制 IP）。

#### Scenario: 内网抓取
- WHEN 从 192.168.1.100 抓取 `/actuator/prometheus`
- THEN 200 + 全部指标

#### Scenario: 公网抓取
- WHEN 从公网 IP 抓取
- THEN 403

#### Scenario: 健康检查公开
- WHEN 从公网抓取 `/actuator/health`
- THEN 200 + `{"status":"UP"}`

### Requirement: Access Log

所有 `/api/schedule/v1/*` 与 `/a2a/v1/*` 请求 MUST 写 AccessLog（含 `method` / `path` / `status` / `durationMs` / `userId` / `ip` / `userAgent` / `ts`），落 `schedule_access_log` 表（与 audit log 不同表，独立索引）。`userId` 从 JWT 解析（如有）；无 JWT 记 `null` + `actor="anonymous"`。

#### Scenario: 正常请求
- WHEN POST `/api/schedule/v1/events`
- THEN `schedule_access_log` 新增一行

#### Scenario: 高频写入保护
- WHEN QPS > 100
- THEN AccessLog 走批量写入（每 5s flush 一次或累积 1000 条 flush）
