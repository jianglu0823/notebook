# Spec: C1 A2A 协议服务端

`schedule-agent` 作为 A2A Server Agent，对外暴露标准 JSON-RPC 2.0 任务端点，遵循 A2A 任务生命周期（`submitted` → `working` → `completed` / `input-required` / `failed`）。

## ADDED Requirements

### Requirement: Agent Card 端点

`GET /a2a/v1/agent-card` MUST 返回 `AgentCard` JSON，schema 包含 `name` / `description` / `url` / `version` / `capabilities` / `skills` 字段。`skills` 数组 MUST 至少包含 `create_event` / `smart_schedule` / `deep_thinking_schedule` / `ical_export` / `checkin` / `emoji_resolve` 共 6 个 skill，每个 skill 含 `id` / `name` / `description` / `inputSchema`（JSON Schema 描述）。响应 MUST 在 50ms 内返回。

#### Scenario: 首次拉取 Agent Card
- WHEN 客户端 GET `/a2a/v1/agent-card`
- THEN 返回 200，响应体含 6 个 skill，每个 skill 有完整 `inputSchema`
- AND `capabilities.streaming=true`、`capabilities.pushNotifications=false`

#### Scenario: Agent Card 字段缺失返回 500
- WHEN 配置中缺失必填字段（如 `name`）
- THEN 返回 500 + 错误体 `{"error":"MISSING_AGENT_CARD_FIELD","field":"name"}`

### Requirement: 任务提交端点（同步）

`POST /a2a/v1/tasks/send` MUST 接受 JSON-RPC 2.0 风格请求，body 含 `jsonrpc="2.0"` / `id` / `method="tasks/send"` / `params.taskType` / `params.input` (含 `TextPart` 或 `DataPart`)。响应 MUST 在 P95 < 500ms 返回，body 含 `id` / `jsonrpc` / `result.task`(含 `id`/`status`/`artifacts`)/ 或 `error`。`params.input` 类型为 `TextPart`(自然语言)时 MUST 走 NLU 解析；为 `DataPart`(结构化 `DataValue` 字典)时 MUST 跳过 NLU 直接落库。

#### Scenario: 自然语言建日程
- WHEN Client POST `tasks/send`，`params.input.parts=[{"type":"text","text":"明天下午3点开会，1小时"}]`
- THEN 返回 200，response `result.task.status="completed"`，`result.task.artifacts[0].type="schedule_card"`，含 `eventId` / `startTime` / `endTime` / `title="会议"`
- AND MySQL `schedule_event` 表新增一行，NLU 解析的时间已落库

#### Scenario: DataPart 跳过 NLU
- WHEN Client POST `tasks/send`，`params.input.parts=[{"type":"data","data":{"action":"create","title":"复盘","startTime":"2026-08-07T15:00:00+08:00","endTime":"2026-08-07T16:00:00+08:00"}}]`
- THEN 直接落库，无 NLU 调用；返回 200 + `status="completed"`

#### Scenario: 必填参数缺失返回 400
- WHEN 缺 `params.taskType`
- THEN 返回 400 + `error.code="INVALID_PARAMS"`、`error.message="taskType required"`

#### Scenario: A2A 协议版本不匹配
- WHEN 请求 `jsonrpc="1.0"`
- THEN 返回 400 + `error.code="INCOMPATIBLE_PROTOCOL_VERSION"`

### Requirement: 任务订阅端点（SSE 流式）

`POST /a2a/v1/tasks/sendSubscribe` MUST 接受与 `tasks/send` 相同的 params，响应 MUST 走 `Content-Type: text/event-stream`，按 A2A 规范推送 `TaskStatusUpdateEvent` 序列。事件流 MUST 按 `submitted` → `working` → `completed` 顺序推送；中途若需用户确认则推送 `input-required` 状态并暂停。每个事件 MUST 含 `taskId` / `status` / `timestamp` 字段。

#### Scenario: 流式排期订阅
- WHEN Client POST `tasks/sendSubscribe`，`params.taskType="smart_schedule"`
- THEN 响应以 SSE 推送：先 `data: {"taskId":"t1","status":"submitted"}\n\n`
- THEN 接着 `data: {"taskId":"t1","status":"working","progress":0.3}\n\n`
- THEN 最后 `data: {"taskId":"t1","status":"completed","artifacts":[...]}\n\n`
- THEN 关闭连接

#### Scenario: 冲突触发 input-required
- WHEN 用户已有 14:00 会议，Client 提交 14:00 新建请求
- THEN 推送 `status="input-required"` 事件，body 含 `artifacts[0].data.suggestions`（Top-3 替代时段）
- AND 任务进入等待状态，连接保持打开，等待用户回复

### Requirement: 任务状态机

服务端 MUST 严格遵循 A2A 状态机：`submitted` → `working` → (`completed` | `input-required` | `failed`)，不得跳跃。`input-required` 状态 MUST 支持后续 `tasks/send` 携带 `params.taskId` 续传（"用户回复"语义）。`failed` 状态 MUST 含 `error.code` 与 `error.message`。

#### Scenario: 状态合法流转
- WHEN 创建任务 → NLU 解析成功 → 落库成功
- THEN 状态序列为 `submitted` → `working` → `completed`

#### Scenario: 状态非法跳跃
- WHEN 实现错误直接返回 `completed` 跳过 `working`
- THEN 单元测试断言：`IllegalStateException` 必须被捕获并返回 `failed` 状态

#### Scenario: input-required 续传
- WHEN Client 收到 `input-required` 后再次 POST `tasks/send`，body 含 `params.taskId="t1"` + 用户选择
- THEN 原任务恢复，最终返回 `completed` 状态；`result.task.id` 与原 `t1` 一致

### Requirement: 幂等键

`POST /a2a/v1/tasks/send` 与 `tasks/sendSubscribe` MUST 支持 `params.idempotencyKey`（或 `X-Idempotency-Key` header），24h 内同 key MUST 返回首次调用的结果（含 taskId），不得重复建事件。幂等键不合法（长度 > 128 或含非 ASCII）MUST 返回 400。

#### Scenario: 同 key 重复调用
- WHEN 第一次 POST `idempotencyKey="abc-123"` 创建事件成功
- WHEN 第二次 POST 同 `idempotencyKey="abc-123"`
- THEN 返回相同 `taskId`，`schedule_event` 表无新行

#### Scenario: 24h 后同 key
- WHEN 24h 之后再次 POST 同 key
- THEN 视为新请求，正常处理（缓存已过期）

### Requirement: A2A 错误响应

所有错误响应 MUST 包含 `error.code`（枚举：`INVALID_PARAMS` / `TASK_NOT_FOUND` / `CONFLICT` / `PERMISSION_DENIED` / `INTERNAL_ERROR` / `AGENT_UNAVAILABLE`）与 `error.message`（人类可读中文）。`INTERNAL_ERROR` MUST 同时写入 `schedule_audit_log`，actor=`system`。

#### Scenario: 内部异常被捕获
- WHEN LLM 调用超时
- THEN 返回 500 + `error.code="AGENT_UNAVAILABLE"`、`error.message="日程服务暂时不可用，请稍后重试"`
- AND `Retry-After: 30` header 出现

#### Scenario: 冲突返回 409
- WHEN 用户提交的时间与已有事件冲突
- THEN 返回 409 + `error.code="CONFLICT"`、`error.message="14:00 已有『周会』"`
