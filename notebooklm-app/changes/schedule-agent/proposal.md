# Proposal: AI 伴侣智能日程与 A2A 协作系统（schedule-agent）

## Why

`LLM-note` 当前已落地 4 大应用域（QA 问答 / 播客 / 小红书工作台 / 智能体小镇 / 伴侣聊天），
但**没有任何"时间维度"的产品形态**：用户安排会议、习惯打卡、待办都靠外部日历或记事本，
与 AI 伴侣对话中提到"明天下午 3 点开会"时，伴侣只能凭空编一个时间，无法真正落库、无法冲突检测、
无法打卡闭环。

`PRD:AI 伴侣智能日程与 A2A 协作系统 V1.0` 定义了完整愿景：自然语言建日程、智能排期、
执行闭环、跨端同步，并以 A2A (Agent-to-Agent) 协议实现伴侣 Agent 与日程 Agent 的解耦。
本变更先把 PRD 范围全量落地到一个可用版本，作为整套 AI 伴侣系统的"时间维度"基础。

同时，A2A 协议是本项目未来所有"能力解耦"的标准范式：
- 当 `companion-chat` 后续版本需要新增"查天气""查快递"等 Agent 时，可复用本变更的 A2A 契约；
- 当第三方日历（飞书 / Google / Apple）需要接入时，只需新增一个 A2A Client Adapter。

## What Changes

1. **独立微服务 `schedule-agent`**：
   - 全新顶级 Maven 项目 `/Users/jianglu/claudeProject/LLM-note/schedule-agent/`
   - 3 个 Maven 模块：`schedule-agent-server`（Spring Boot 3.3.5 主进程）/ `schedule-agent-client-sdk`
     （Java SDK，供 companion-chat 等后续 Agent 复用 A2AClient + ScheduleTool）/ `schedule-agent-contract`
     （A2A 协议 DTO + 共享枚举）
   - 主进程端口 `8081`，路径前缀 `/a2a/v1/*`，与现有 `notebooklm-app`(8080) 平行部署

2. **A2A 协议服务端**：
   - `POST /a2a/v1/tasks/send`：JSON-RPC 2.0 风格任务提交，同步返回 `Task` 状态
   - `POST /a2a/v1/tasks/sendSubscribe`：SSE 流式任务订阅，`Content-Type: text/event-stream`
   - `GET /a2a/v1/agent-card`：返回 `AgentCard` 元数据（skills 列表 + capabilities 声明）
   - 支持 4 种任务状态：`completed` / `working` / `input-required` / `failed`
   - 任务输入类型：自然语言文本（`TextPart`）/ 结构化 `DataPart`（含 ISO8601 时间窗）

3. **日程事件 CRUD**：
   - 12 张表建在独立 MySQL schema `schedule_agent`（与 `nblm` 隔离）：
     `schedule_event` / `schedule_attendee` / `schedule_checkin` / `schedule_focus_session` /
     `schedule_emoji_dict` / `schedule_agent_card` / `schedule_task_artifact` /
     `schedule_idempotency` / `schedule_external_sync` / `schedule_user_pref` /
     `schedule_metric_event` / `schedule_audit_log`
   - 事件字段含 `priority` (P0/P1/P2) / `location` / `attendees` / `recurrence` (RRULE) /
     `syncStatus` (PENDING/SYNCED/FAILED) / `emoji` / `idempotencyKey`
   - 4 种操作：`create` / `list` / `update` / `delete`，全 REST 化 (`/api/schedule/v1/events`)

4. **智能排期（SmartScheduler）**：
   - 输入：用户偏好（"上午不排会"）+ 任务列表 + 已有事件
   - 输出：Top-3 推荐时段（每段含 `start / end / score / reason`）
   - 冲突检测：滑动窗口扫描已有事件，返回 `conflict_reason`（"已与『周会』时间重叠"）
   - 替代方案：冲突时主动提供 2-3 个邻近时段供用户选择
   - 算法 v1：基于规则（工作日 9-19 点 / 周末 10-22 点 / 30 分钟最小粒度）
   - 算法 v2：通过 LLM 深度思考（`C5` 能力）做多目标冲突下的 P0/P1/P2 切片

5. **深度思考排期（LLM 增强）**：
   - 当用户面临多目标冲突（"周末既要写论文又要陪家人"）时触发
   - 调用 DashScope `glm-4.6` 做 P0/P1/P2 优先级切片，输出时间块 + 理由
   - 失败降级：LLM 超时/异常时自动回退到规则版 Top-3
   - 缓存：相同 (userId + tasks_hash + dateRange) 24h 内复用

6. **本地日历桥（LocalCalendarBridge）**：
   - 接口：`POST /api/schedule/v1/internal/local-calendar/sync`
   - 默认模式：**Mock**（写日志 + 生成 iCal 文件到 `~/.schedule-agent/calendar.ics`）
   - `mac-local` profile 启用真实 AppleScript（`osascript` 调用 Calendar.app）
   - 权限检测：执行前检查 "Automation Permission"，无权限返回 `PERMISSION_DENIED` + 引导文案
   - 3 种操作：`CREATE` / `UPDATE` / `DELETE`，全幂等

7. **iCal (.ics) 导出**：
   - 使用 `biweekly 0.6.4` 库（Java 生态 RFC 5545 事实标准）
   - 单事件导出：`GET /api/schedule/v1/events/{id}/ical`
   - 范围导出：`GET /api/schedule/v1/events/ical?from=2026-08-01&to=2026-08-31`
   - 含 VALARM 提醒（默认 15 分钟前 + 事件开始时）

8. **专注计时器 + 打卡**：
   - 启动：`POST /api/schedule/v1/events/{id}/focus/start` → 返回 `sessionId`
   - 心跳：`POST /api/schedule/v1/focus/{sessionId}/heartbeat`（每 60s 一次）
   - 完成打卡：`POST /api/schedule/v1/focus/{sessionId}/complete`
   - 连续统计：同 `eventId` 在 24h 内完成打卡记为一次"完成"，连续 7 天触发 milestone
   - 抗作弊：心跳缺失超过 5 分钟自动标记 `focusStatus=ABANDONED`

9. **主题 Emoji 匹配**：
   - 30+ 模板字典（如"开会"→📅、"健身"→🏃、"吃饭"→🍽、"学习"→📚、"看医生"→🏥）
   - 匹配规则：title/description 文本相似度（Jaccard + 关键词命中）
   - 未命中走 fallback "📌"
   - 字典种子文件：`schedule-agent-server/src/main/resources/emoji-dict.json`

10. **外部日历同步钩子**：
    - 抽象接口 `ExternalCalendarAdapter`（`create / update / delete / list`）
    - 默认实现 `MockExternalCalendarAdapter`（写日志 + 落 JSON）
    - 飞书 / Google 占位实现：`LarkCalendarAdapter` / `GoogleCalendarAdapter`（仅 `TODO` 注释 + 抛 `NotImplementedException`）
    - 真实 OAuth 流程不在本期范围（non-goal）

11. **数据隐私**：
    - `title` / `description` / `location` 字段 AES-256-GCM 加密落 DB（MySQL `VARBINARY(1024)`）
    - 密钥从 `SCHEDULE_AGENT_ENC_KEY` 环境变量读（Base64 32 字节）
    - 查询时内存解密，返回前端走 HTTPS
    - 审计日志：所有 CRUD 写 `schedule_audit_log`（含 actorId / action / before / after / ts）

12. **埋点指标**：
    - Micrometer + `/actuator/prometheus`
    - 关键指标：
      `schedule.a2a.request.duration`（A2A 延迟，PRD 要求 < 500ms）/
      `schedule.event.confirm.rate`（AI 推荐→用户确认转化率，PRD 要求 ≥ 75%）/
      `schedule.checkin.completion.rate`（打卡完成率，PRD 要求 ≥ 60%）/
      `schedule.event.create.count`（按 priority 分桶）

## Scope

### In

- 全新顶级 Maven 项目 `schedule-agent/`，3 个模块
- 12 张 MySQL 表，schema 隔离在 `schedule_agent`
- A2A 协议服务端（JSON-RPC 2.0 + SSE），路径 `/a2a/v1/*`
- REST API `/api/schedule/v1/*`（CRUD / focus / ical / 内部 local-calendar）
- A2A Client SDK（`schedule-agent-client-sdk` 模块）：`A2AClient` + `ScheduleTool` + DTO
- 30+ Emoji 字典种子 + Jaccard 匹配服务
- 集成测试：`schedule-agent-server/src/test/java/.../integration/` 包含 "mock companion" 客户端
- `changes/schedule-agent/migration.sql`：12 张表 + 索引 + 字符集 `utf8mb4_unicode_ci`

### Out (Non-goals)

- ❌ Companion Agent 本体（归属 `companion-chat` 变更，本期不实现）
- ❌ 真实 Nacos 服务部署（默认 in-memory 注册，prod profile 通过配置切到 Nacos URL）
- ❌ 飞书 / Google Calendar 真实 OAuth 流程（只留接口契约 + Mock 实现）
- ❌ 生产级鉴权（mTLS / OIDC）— 仅做内部 Token 鉴权（`X-Schedule-Agent-Token` header）
- ❌ 分布式链路追踪（Jaeger / OpenTelemetry）— 仅 AccessLog + Micrometer
- ❌ 飞书 / 苹果 iCloud 系统级推送 — 只生成 iCal 文件 + 系统提醒 API hook
- ❌ 智能体小镇中的"村民日程"集成（属 `world` 模块，单独需求）
- ❌ 排期算法的 LLM 强化学习 / 用户反馈闭环（v3 才考虑）

## Capabilities

本次变更触及以下 12 项能力，每项均有独立 `specs/<capability>/spec.md`：

| ID  | Capability                | Spec 路径                              |
| --- | ------------------------- | -------------------------------------- |
| C1  | A2A 协议服务端            | `specs/c1-a2a-protocol-server/spec.md` |
| C2  | Agent Card 注册 + 发现    | `specs/c2-agent-card-registration/spec.md` |
| C3  | 日程事件 CRUD             | `specs/c3-schedule-event-crud/spec.md` |
| C4  | 智能排期（规则引擎）      | `specs/c4-smart-scheduler/spec.md`     |
| C5  | 深度思考排期（LLM 增强）  | `specs/c5-deep-thinking-scheduling/spec.md` |
| C6  | 本地日历桥                | `specs/c6-local-calendar-bridge/spec.md` |
| C7  | iCal 导出                 | `specs/c7-ical-export/spec.md`         |
| C8  | 专注计时器 + 打卡         | `specs/c8-focus-timer-checkin/spec.md` |
| C9  | 主题 Emoji 匹配           | `specs/c9-emoji-mapping/spec.md`       |
| C10 | 外部日历同步钩子          | `specs/c10-external-calendar-sync/spec.md` |
| C11 | 数据隐私（加密）          | `specs/c11-data-privacy/spec.md`       |
| C12 | 埋点指标                  | `specs/c12-observability/spec.md`      |

## Impact

- **新增代码**:`schedule-agent/` 顶级项目（含 3 个 Maven 子模块），预计 50~70 个 Java 文件
- **新增表**:12 张（全部位于 `schedule_agent` schema，与 `nblm` 数据库物理隔离）
- **对 `companion-chat` 的影响**:无（client-sdk 通过 `mvn dependency` 形式提供，不强制依赖）
- **对 `infra/mysql/init.sql` 的影响**:追加 `CREATE DATABASE schedule_agent` + `USE schedule_agent` + 12 张表
- **对 gateway / docker-compose 的影响**:新增 `schedule-agent` 服务条目（端口 8081）
- **对 SDK 体积的影响**:`biweekly 0.6.4` 新增依赖（约 200KB），DashScope SDK 复用（同 `notebooklm-app` 版本）
- **对运维的影响**:新增 1 个 Spring Boot 进程 + 1 个 schema；监控面板需新增 4 个指标

## Success Criteria

- 12 个能力 spec 全部含 SHALL/MUST 级断言 + WHEN/THEN Scenario
- `mvn -o clean compile` 与 `mvn -o test` 在 3 个模块全绿
- A2A `tasks/send` 端到端延迟 P95 < 500ms（PRD 要求）
- Mock LocalCalendar 在 `mac-local` profile 关闭时不阻塞，App 起停 5s 内完成
- 集成测试覆盖：自然语言建日程 / 冲突检测 / 深度排期 / Mac 日历写入 / iCal 导出 / 打卡 6 大主路径
- `companion-chat` 后续版本可通过 `mvn dependency` 引入 `schedule-agent-client-sdk` 并运行 `ScheduleTool` 冒烟
