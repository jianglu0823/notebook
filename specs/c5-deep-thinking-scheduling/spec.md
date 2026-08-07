# Spec: C5 深度思考排期（LLM 增强）

当用户面临多目标冲突（如"周末既要写论文又要陪家人"）时，调用 LLM 做 P0/P1/P2 优先级切片，输出时间块 + 理由。

## ADDED Requirements

### Requirement: 触发条件

深度排期 MUST 仅在以下任一条件满足时触发：
1. 任务数 ≥ 3 且优先级分布含 P0
2. 用户明确指令"深度思考""帮我规划"
3. C4 规则引擎返回的 `score` 最高候选 < 60（即规则算法无最优解）
其他场景走 C4 规则引擎。

#### Scenario: 3 任务含 P0 触发
- WHEN POST `/api/schedule/v1/scheduler/recommend` 携带 3 任务（其中 1 个 P0）
- THEN 内部走 LLM 排期，不走 C4 规则

#### Scenario: 关键字触发
- WHEN 任务 title 含"深度思考"或 description 含"帮我规划"
- THEN 强制走 LLM

#### Scenario: 低分触发
- WHEN C4 规则引擎 Top-1 候选 `score=55`（< 60）
- THEN 自动 fallback 到 LLM 重排

### Requirement: LLM Prompt 契约

LLM 调用的 system prompt MUST 固定为 Schedule Agent System Prompt（PRD 附录），user prompt MUST 含 JSON 结构：任务列表 + 已有事件 + 用户偏好 + 候选日期范围。LLM 输出 MUST 为严格 JSON，含 `timeBlocks` 数组，每项 `[{title, priority, startTime, endTime, reason}]`，`reason` 字段 1~2 句中文。

#### Scenario: Prompt 注入
- WHEN 调 LLM
- THEN system prompt 严格按 PRD 附录的 4 条规则（"模糊时间必须确认""冲突时给替代""输出严格 A2A Artifact 格式""语气简洁专业"）

#### Scenario: LLM 输出解析失败
- WHEN LLM 返回非 JSON 文本
- THEN 标记 `LLM_PARSE_FAILED`，自动 fallback 到 C4 规则引擎 Top-3

### Requirement: P0/P1/P2 优先级切片

LLM 输出 MUST 按优先级排序时间块：P0 优先占用黄金时段（上午 9-12 + 下午 14-17），P1 次之，P2 安排在边缘时段或周末。同一 P0 任务冲突时 MUST 由 LLM 显式裁决（"放弃 A 改 B"），裁决理由写入 `reason` 字段。

#### Scenario: 3 任务 P0/P1/P2 切片
- WHEN LLM 收到 3 任务：写论文(P0, 4h) / 陪家人(P1, 3h) / 整理房间(P2, 1h)
- THEN 返回：论文周六 9:00-13:00 / 陪家人周六 14:30-17:30 / 整理房间周日 10:00-11:00
- AND `reason` 字段解释"论文优先占用上午连续时间，家人安排在下午精力转折点"

#### Scenario: 同一 P0 冲突裁决
- WHEN 2 个 P0 任务时段冲突
- THEN LLM 输出一项裁决理由（如"考虑用户提到 deadline 是周一，论文优先"），另一项被推迟

### Requirement: 降级与超时

LLM 调用超时 MUST 为 10s（`schedule-agent.llm.timeout=10s`），超时或异常 MUST 自动 fallback 到 C4 规则引擎返回的 Top-3，并打 WARN 日志 + `schedule_metric_event` 表新增 `DEEP_SCHEDULE_FALLBACK` 事件。

#### Scenario: LLM 超时
- WHEN LLM 10s 内未响应
- THEN 取消 Future，返回 C4 规则结果；`Retry-After: 0`；日志 `WARN: deep schedule LLM timeout, fallback to rule engine`

#### Scenario: LLM 余额耗尽
- WHEN DashScope 返回 402 / 429
- THEN 走降级，状态码 200 + 响应头 `X-Schedule-Fallback-Reason: LLM_QUOTA_EXHAUSTED`

### Requirement: 结果缓存

相同 `(userId, tasksHash, dateRangeHash)` 组合 24h 内 MUST 复用缓存结果。缓存键在 `schedule_audit_log` 记录 `LLM_CACHE_HIT` 事件。

#### Scenario: 24h 内同请求
- WHEN 第二次 POST 完全相同的 tasks + dateRange
- THEN 响应时间 < 50ms；audit log 标记 `LLM_CACHE_HIT=true`

#### Scenario: 24h 后
- WHEN 第二次请求 24h+ 后
- THEN 重新调 LLM；audit log 标记 `LLM_CACHE_HIT=false`

### Requirement: 模型选择

LLM 模型 MUST 走 `schedule-agent.llm.model` 配置项（默认 `glm-4.6`，与 notebooklm-app 现有 ChatModelFactory 模型一致）。模型不可用时 MUST fallback 到 `glm-4-flash`（更便宜更快）。两者都失败 MUST 走 C4 规则。

#### Scenario: 主模型调用
- WHEN 启动 `glm-4.6` 可用
- THEN 用 `glm-4.6` 调 LLM

#### Scenario: 主模型失败降级
- WHEN `glm-4.6` 返回 5xx
- THEN 自动切 `glm-4-flash` 重试一次；仍失败走 C4
