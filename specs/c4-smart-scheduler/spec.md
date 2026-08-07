# Spec: C4 智能排期（规则引擎）

基于规则的智能排期：输入任务列表 + 用户偏好 + 已有事件，输出 Top-3 推荐时段。算法 v1 不依赖 LLM，可独立运行。

## ADDED Requirements

### Requirement: 推荐时段生成

`POST /api/schedule/v1/scheduler/recommend` MUST 接受 JSON body，含 `tasks` (数组，每项 `{title, durationMinutes, priority, preferredWindow?}`) / `dateRange` (`{from, to}`) / `existingEventIds` (可选, 数组)。响应 MUST 含 `recommendations` 数组，每项 `[{start, end, score, reason}]`，按 `score DESC` 排序，最多 3 条。

#### Scenario: 单任务推荐
- WHEN POST 1 个任务（90 分钟, P1, 工作日 9-18 点）
- THEN 返回最多 3 个候选时段，每段 90 分钟，落在工作日 9:00~17:30 之间
- AND `score` 越高越优

#### Scenario: 0 候选时段
- WHEN `dateRange` 内全部时段都被已有事件占满
- THEN 返回 `recommendations: []` + 提示 `error.message="dateRange 内无空闲时段"`

#### Scenario: 多个任务批量排
- WHEN POST 3 个任务
- THEN 返回 3 个任务的 Top-3 时段，任务之间不重叠

### Requirement: 冲突检测

排期算法 MUST 在生成候选前扫描 `existingEventIds` 对应事件（不传则查用户全部 `status='active'` 事件），滑动窗口检测时间重叠。检测到冲突 MUST 跳过该候选时段。

#### Scenario: 检测到冲突跳过
- WHEN 已有事件 14:00-15:00，候选 14:30-15:30
- THEN 候选被跳过；冲突事件标题写入 `conflict_reason` 字段返回（仅 debug 接口，生产接口不返回）

#### Scenario: 部分重叠
- WHEN 已有 14:00-15:00，候选 14:30-15:00
- THEN 视为冲突，跳过

### Requirement: 用户偏好

`schedule_user_pref` 表 MUST 存储用户偏好，字段含 `userId` / `noMorningMeetings` (bool) / `noEveningWork` (bool) / `workdayStart` (time, 默认 09:00) / `workdayEnd` (time, 默认 19:00) / `weekendStart` / `weekendEnd` / `minSlotMinutes` (默认 30)。偏好 MUST 在生成候选前应用。`GET /api/schedule/v1/users/{userId}/prefs` MUST 返回当前偏好；`PUT` MUST 整体覆盖。

#### Scenario: 上午不排会
- WHEN 用户 `noMorningMeetings=true` 且任务 P0/P1
- THEN 候选时段 MUST 不在 09:00-12:00 之间

#### Scenario: 修改偏好
- WHEN PUT `{workdayEnd:"20:00"}`
- THEN 后续推荐候选时段扩展到 20:00 截止

#### Scenario: 偏好缺失走默认
- WHEN 新用户无 `schedule_user_pref` 记录
- THEN 自动创建默认偏好（全部 false + 默认时间窗）

### Requirement: 评分函数

每个候选时段 MUST 计算 `score` (0~100)，维度含：与偏好时间窗重合度 (40 分) / 与已有事件距离 (20 分，越远越高) / 任务优先级匹配 (20 分, P0 早 > P1 > P2) / 用户历史热点时段 (20 分, 从过去 30 天事件统计)。`reason` 字段 MUST 中文说明前 2 个得分维度（如"上午精力好 + 避开已有会议"）。

#### Scenario: 高分候选
- WHEN 候选落在工作日上午 + 周边无事件 + P0 任务
- THEN `score >= 80`，`reason` 包含"上午精力好"

#### Scenario: 低分候选
- WHEN 候选在周末晚上 + 紧邻已有事件 + P2 任务
- THEN `score <= 50`

### Requirement: 替代方案

冲突时 MUST 在响应 `alternatives` 数组返回 Top-3 邻近时段（前后各 1 小时滑动），每项含 `start` / `end` / `conflictWith` (冲突事件标题)。`alternatives` 与 `recommendations` 互斥（无冲突时 `alternatives=[]`）。

#### Scenario: 冲突触发替代
- WHEN 用户提交 14:00 新建，14:00 已有周会
- THEN `recommendations` 含冲突后的 3 个邻近候选；`alternatives` 含 3 个替代时段

### Requirement: 排期性能

P95 响应延迟 MUST < 200ms（不含 LLM 调用）。30 天范围内、10 个已有事件、5 个任务的排期请求 MUST 在 200ms 内返回。

#### Scenario: 性能基线
- WHEN 30 天范围 + 10 已有 + 5 任务
- THEN `micrometer.timer("schedule.scheduler.recommend")` P95 < 200ms
