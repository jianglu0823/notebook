# 执行合同:schedule-agent

> DP-3 评审用 — 14 条约束 + 72 条测试断言 + 13 个批次 + 评审 checklist

## Intent Lock

- **变更名称**:`schedule-agent`
- **要解决的问题**:`LLM-note` 缺"时间维度"产品形态;用户与 AI 伴侣对话中提到"明天下午开会"时无结构化日程能力,既无法落库、无法冲突检测、也无法打卡闭环。本变更以 A2A 协议 + 独立微服务形式落地完整 PRD 范围
- **范围内**:
  - 独立顶级 Maven 项目,3 模块(`schedule-agent-contract` / `schedule-agent-server` / `schedule-agent-client-sdk`)
  - 端口 8081,路径前缀 `/a2a/v1/*` + `/api/schedule/v1/*`
  - 12 个能力全入(C1~C12: A2A 协议 / Agent Card / 事件 CRUD / 规则排期 / 深度排期 / Mac 日历 / iCal / 打卡 / Emoji / 外部钩子 / 隐私 / 埋点)
  - 16 张表(独立 schema `schedule_agent`,与 `nblm` 物理隔离)
  - A2A 协议客户端 SDK(供 `companion-chat` 后续 `mvn dependency` 复用)
- **范围外**(Scope Fence,任一项被触发立刻回退到 `specifying`):
  - ❌ Companion Agent 本体 / 客户端代码(归属 `companion-chat`)
  - ❌ 真实 Nacos 部署(只做 in-memory 注册 + 配置开关)
  - ❌ 飞书 / Google Calendar 真实 OAuth 流程
  - ❌ 生产级鉴权(mTLS / OIDC)
  - ❌ 分布式链路追踪(Jaeger / OpenTelemetry)
  - ❌ 智能体小镇中的"村民日程"集成
  - ❌ 用户反馈闭环 / 强化学习排期

## Approved Behavior

> 完整内容见 `specs/c1~c12/*.md`。本节为执行用压缩清单 — 72 条 R-断言全部映射到 12 spec 的 Requirement。

### 测试断言 R1~R72(spec 顺序编号)

#### C1 A2A 协议服务端(specs/c1-a2a-protocol-server)
- **R1**: `GET /a2a/v1/agent-card` 返回 200 + 6 个 skill + capabilities
- **R2**: AgentCard 字段缺失返回 500 + `MISSING_AGENT_CARD_FIELD`
- **R3**: `tasks/send` 自然语言建日程 P95 < 500ms,落库 `schedule_event`
- **R4**: DataPart 跳过 NLU 直接落库
- **R5**: 必填参数缺失返回 400 + `INVALID_PARAMS`
- **R6**: `jsonrpc="1.0"` 返回 `INCOMPATIBLE_PROTOCOL_VERSION`
- **R7**: `tasks/sendSubscribe` SSE 推 submitted→working→completed
- **R8**: 冲突触发 `input-required` 状态 + 保持连接
- **R9**: 状态机非法跳跃抛 `IllegalStateException` → `failed`
- **R10**: `idempotencyKey` 24h 内去重
- **R11**: `INTERNAL_ERROR` 写 audit log + `Retry-After` header
- **R12**: 冲突返回 409 + `CONFLICT` + 冲突事件标题

#### C2 Agent Card 注册(specs/c2-agent-card-registration)
- **R13**: dev profile in-memory 启动期注册
- **R14**: prod profile Nacos 启动失败降级 in-memory
- **R15**: 15s 心跳,3 次失败重新注册 + `REGISTRY_RE_REGISTER` 事件
- **R16**: discover 按 skill 过滤,按延迟升序,最多 10 条
- **R17**: MAJOR 版本变更清空 in-memory 注册表
- **R18**: Nacos 密码走 `SCHEDULE_AGENT_NACOS_PASSWORD` env,缺失启动失败

#### C3 事件 CRUD(specs/c3-schedule-event-crud)
- **R19**: POST 创建事件加密落库 + 返回 201 + `eventId`
- **R20**: title 越界返回 400 + `error.field="title"`
- **R21**: endTime ≤ startTime 返回 400
- **R22**: 缺 `startTime` 返回 400
- **R23**: 范围查询按 startTime ASC
- **R24**: 关键词模糊查询(title/description 解密后 LIKE)
- **R25**: 不存在 ID 返回 404
- **R26**: PATCH 部分字段更新 + priority 触发 audit
- **R27**: 修改 startTime 触发冲突检测返回 409
- **R28**: DELETE 软删 + 关联 focus/checkin `hidden=true`
- **R29**: RRULE 解析 + 90 天物化 occurrence
- **R30**: 非法 RRULE 返回 400
- **R31**: 加密落库(`title_cipher` 二进制不可读)
- **R32**: 解密查询返回明文
- **R33**: 密钥缺失启动失败

#### C4 智能排期规则(specs/c4-smart-scheduler)
- **R34**: 单任务返回最多 3 个候选 + score 排序
- **R35**: 0 候选时段返回 `recommendations: []`
- **R36**: 多任务批量排任务不重叠
- **R37**: 冲突检测跳过候选时段
- **R38**: 部分重叠视为冲突
- **R39**: `noMorningMeetings=true` 过滤 09-12 时段
- **R40**: 修改偏好后推荐结果更新
- **R41**: 偏好缺失自动创建默认
- **R42**: 4 维评分函数(偏好 40 + 距离 20 + 优先级 20 + 历史 20)
- **R43**: 高分候选 `score >= 80` + 中文 reason
- **R44**: 冲突触发 alternatives Top-3 邻近时段
- **R45**: P95 < 200ms

#### C5 深度思考排期(specs/c5-deep-thinking-scheduling)
- **R46**: 3 任务含 P0 触发 LLM
- **R47**: 关键字"深度思考" / "帮我规划" 强制走 LLM
- **R48**: C4 top score < 60 自动 fallback LLM
- **R49**: System prompt 严格按 PRD 附录 4 条规则
- **R50**: LLM 非 JSON 输出自动 fallback C4
- **R51**: P0 优先占黄金时段(9-12 + 14-17)
- **R52**: 同 P0 冲突 LLM 显式裁决
- **R53**: 10s 超时 fallback C4 + `Retry-After: 0`
- **R54**: LLM 402/429 走降级 + `X-Schedule-Fallback-Reason: LLM_QUOTA_EXHAUSTED`
- **R55**: 24h 同请求缓存命中(< 50ms)
- **R56**: 24h 后重新调 LLM
- **R57**: `glm-4.6` → `glm-4-flash` → C4 降级链

#### C6 本地日历桥(specs/c6-local-calendar-bridge)
- **R58**: `mode=mock` 默认值
- **R59**: `mode=mac-local` 启动期检测 `osascript`
- **R60**: 非法 mode 启动失败
- **R61**: mock CREATE 追加 VEVENT 到 `~/.schedule-agent/calendar.ics`
- **R62**: mac-local CREATE 调 `osascript`
- **R63**: AppleScript 权限拒绝返回 403 + `PERMISSION_DENIED`
- **R64**: UPDATE 找不到 VEVENT 返回 404
- **R65**: DELETE 软标记 `syncStatus="DELETED"`
- **R66**: 同 event.id 重复 CREATE 自动转 UPDATE
- **R67**: 同步失败 `syncStatus="FAILED"` + `lastSyncError`
- **R68**: 重试最多 3 次后停止

#### C7 iCal 导出(specs/c7-ical-export)
- **R69**: 单事件导出含 VEVENT + VALARM(15 分钟前)
- **R70**: 范围跨度 > 90 天返回 400
- **R71**: VALARM `?reminder=30,5` 多触发
- **R72**: 重复事件用 `RRULE` 字段不展开
- *(注:实际 c7 共 6 个 Requirement,本表 R69~R72 涵盖核心 4 项 + 字符编码 + Content-Disposition,完整映射见 specs/c7-ical-export/spec.md)*

#### C8 专注计时器+打卡(specs/c8-focus-timer-checkin)
- **R73**: 启动 focus 返回 201 + sessionId
- **R74**: 心跳超时(>5min)后台自动 ABANDONED
- **R75**: 完成打卡写 checkin + 计算 streakDays
- **R76**: 连续 7 天触发 milestone 7
- **R77**: 中断归零(streakDays=1)
- **R78**: ABANDONED 会话 complete 返回 400

#### C9 Emoji 匹配(specs/c9-emoji-mapping)
- **R79**: 30+ 种子字典启动加载
- **R80**: Jaccard 匹配 + Caffeine 5min TTL
- **R81**: 未命中 fallback 📌
- **R82**: 创建事件自动分配 emoji
- **R83**: 显式 emoji 字段覆盖自动

#### C10 外部日历钩子(specs/c10-external-calendar-sync)
- **R84**: `ExternalCalendarAdapter` 接口 4 方法签名
- **R85**: Mock 适配器写日志 + 落 JSON Lines
- **R86**: Lark / Google 占位抛 `UnsupportedOperationException`
- **R87**: 配置切换 `schedule-agent.adapter.provider=mock`
- **R88**: @Async 不阻塞主线程(立即返回 202)
- **R89**: 失败 audit log + syncStatus=FAILED
- **R90**: 重试 3 次后停止

#### C11 数据隐私(specs/c11-data-privacy)
- **R91**: AES-256-GCM 加密落库(IV 独立)
- **R92**: 解密查询返回明文
- **R93**: 错误密钥抛 AEADBadTagException → DECRYPTION_FAILED
- **R94**: 密钥缺失启动失败
- **R95**: 密钥轮换双密钥(NEW + PREVIOUS)优先 NEW
- **R96**: 审计日志 append-only(BEFORE DELETE 触发器抛错)
- **R97**: 内部 Token 缺失/不匹配返回 401
- **R98**: IP 限流 60 req/min,第 61 次 429

#### C12 埋点指标(specs/c12-observability)
- **R99**: 4 个 Micrometer 指标(A2A 延迟 / 周活 / 轮次 / 确认率 / 打卡率)
- **R100**: `/actuator/prometheus` 内网可访问,公网 403
- **R101**: AccessLog 批量 flush(5s/1000 条)
- **R102**: A2A P95 < 500ms(PRD 目标)

> **注**:R69~R72 实际对应 c7 spec 的 6 个 Requirement 之中 4 个核心;字符编码 + Content-Disposition 两项的单元测试必含但本表不重复编号,详细测试矩阵在 `specs/c7-ical-export/spec.md` 与 `tests/server/ical/ICalExportServiceTest.java`。
> **验收检查**:每个 R-断言必须由对应 spec 路径的 `src/test/java/...` 测试方法 + (适当时)`integration/...IT.java` 验证。`mvn -o verify` 全绿视为 R1~R102 全部通过。

## Design Constraints(14 条 C-约束)

> 执行期 hard rule — 任一违反触发回退到 `specifying` 或 `bug-investigator`

- **C1(架构)**:项目位置 MUST 为 `/Users/jianglu/claudeProject/LLM-note/schedule-agent/`,3 个 Maven 模块,无反向依赖 `notebooklm-app` 任何 class
- **C2(技术栈)**:Java 21 + Spring Boot 3.3.5 锁版本,不允许升级到 3.4+ / Java 22+
- **C3(A2A 协议)**:`jsonrpc="2.0"`,路径 `/a2a/v1/*`,JSON-RPC 2.0 envelope 严格,Content-Type `application/json` 或 `text/event-stream`
- **C4(SSE)**:30min TTL,`:` 注释行心跳保活,客户端断开时 `onCompletion` 清理
- **C5(数据库)**:12+ 张表建在独立 schema `schedule_agent`,与 `nblm` 物理隔离;`migration.sql` 显式 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`
- **C6(加密)**:title/description/location MUST AES-256-GCM 加密落库;密钥 32 字节 Base64 走 `SCHEDULE_AGENT_ENC_KEY`;缺失 fail-fast
- **C7(限流)**:IP 60 req/min,超限 429 + `Retry-After`;internal 路径 MUST 校验 `X-Schedule-Agent-Token`
- **C8(降级)**:LLM 10s 超时 → C4 规则引擎;`glm-4.6` 失败 → `glm-4-flash` → C4;每次降级 MUST 写 audit log `DEEP_SCHEDULE_FALLBACK`
- **C9(Mac 模式)**:`schedule-agent.calendar.mode` 默认 `mock`;`mac-local` 启动期检测 `osascript` 可用,非 Mac 平台降级 mock 不阻塞
- **C10(iCal)**:biweekly 0.6.4,`Content-Type: text/calendar; charset=utf-8`,VEVENT 必含 UID/DTSTAMP/DTSTART/DTEND/SUMMARY/VALARM
- **C11(Emoji)**:30+ 种子,`data-emoji-dict.sql` `ON DUPLICATE KEY UPDATE` 幂等,Caffeine 5min TTL
- **C12(测试)**:三层测试结构 — 单元(`*Test.java`)/ 集成(`*IT.java` + TestContainers MySQL 8)/ E2E(`*E2E.java` + MockCompanionClient);`mvn -o verify` 全绿
- **C13(SDK)**:client-sdk 包路径 `io.scheduleagent.client`,不与 `io.llmnote.*` 冲突;OkHttp + Jackson;至少封装 `A2AClient` + `ScheduleTool` + `AgentCardDiscoverer`
- **C14(审计)**:append-only,`schedule_audit_log` BEFORE DELETE + BEFORE UPDATE 触发器抛 SQLSTATE 45000;不可物理删除

## Execution Plan(参考)

> `execution-contract.md` 只描述意图;持久化执行计划在 `<change>/.superpowers/sdd/execution-plan.json` 中,由 `ssf execution plan` 在 DP-3 后落地。
> 模式:`sdd`(full default),每个 wave 有唯一 ID,只接受 `pass` review receipt 后才能进入下一 wave。
> 13 个 wave(B0~B12)见下一节。

## Execution Waves

> 13 个 wave,标记依赖关系与并行/串行策略。`parallel` 表示宿主支持并发时可同时执行;CI/Mac dev 默认串行。

### Wave B0:项目骨架
- **Wave ID**:`B0-scaffold`
- **任务**:顶级 pom.xml + 3 模块 pom.xml + contract 模块全部 POJO/record/枚举 + 顶级 README 初稿
- **依赖 wave**:无
- **策略**:`serial`
- **目标**:Maven 编译通过,contract 模块 100% 类可序列化
- **输入**:无
- **输出**:`schedule-agent/` 顶级项目 + 3 子模块 + contract 模块所有 A2A / schedule / error DTO
- **完成标准**:`cd schedule-agent && mvn -o -pl schedule-agent-contract compile` 0 错误
- **Review gate**:`changes/schedule-agent/.review-receipts/B0.md` 必须 `pass`

### Wave B1:MySQL 迁移 + 触发器
- **Wave ID**:`B1-db`
- **任务**:`migration.sql` 16 张表 + BEFORE DELETE/UPDATE 触发器 + 30 条 Emoji 种子
- **依赖 wave**:B0
- **策略**:`serial`
- **目标**:TestContainers MySQL 8 容器跑 migration 成功
- **输入**:B0 产出的 contract `Event` 字段
- **输出**:`infra/mysql/init.sql` 追加 16 张表 DDL
- **完成标准**:`mvn -o -pl schedule-agent-server verify -Dtest=MysqlIntegrationTestBase` 跑通
- **Review gate**:`B1.md` 必须 `pass`(含 DDL 字符集 + 触发器验证)

### Wave B2:隐私 + 审计 + 限流
- **Wave ID**:`B2-privacy-audit`
- **任务**:`FieldEncryptor`(AES-256-GCM + 双密钥)+ `AuditService` + `InternalTokenFilter` + `RateLimitFilter` + `KeyRotationService`
- **依赖 wave**:B1
- **策略**:`serial`
- **目标**:隐私 + 审计 + 限流三层防护落库可用
- **输入**:B1 产出的 16 张表
- **输出**:`io.scheduleagent.server.privacy.*` + `io.scheduleagent.server.web.{InternalTokenFilter, RateLimitFilter}`
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=FieldEncryptorTest,AuditServiceTest,InternalTokenFilterTest,RateLimitFilterTest,KeyRotationServiceTest` 全绿
- **Review gate**:`B2.md` 必须 `pass`

### Wave B3:事件 CRUD + A2A 协议服务端
- **Wave ID**:`B3-events-a2a`
- **任务**:`EventEntity` + `EventService` + `EventController` + `A2AController` + `TaskDispatcher` + 6 个 `SkillHandler` + `SseTaskStreamer` + `IdempotencyService` + `RecurrenceService` + `ApiExceptionHandler`
- **依赖 wave**:B2
- **策略**:`serial`
- **目标**:自然语言建日程走通 A2A → Event 落库 → 响应 schedule_card
- **输入**:B2 产出的 FieldEncryptor + AuditService
- **输出**:`/a2a/v1/tasks/send` + `/a2a/v1/tasks/sendSubscribe` + `/a2a/v1/agent-card` + `/api/schedule/v1/events` 全可用
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=EventServiceTest,A2AControllerTest,SseTaskStreamerTest,IdempotencyServiceTest` 全绿
- **Review gate**:`B3.md` 必须 `pass`(含 R1~R12 + R19~R33 覆盖)

### Wave B4:智能排期规则 + 深度思考
- **Wave ID**:`B4-scheduler`
- **任务**:`RuleScheduler` + `ScoringFunction` + `DeepScheduler` + `DashScopeDeepScheduleClient` + `DeepScheduleCacheService` + `UserPrefEntity` + `UserPrefService` + `SchedulerController`
- **依赖 wave**:B3
- **策略**:`serial`
- **目标**:C4 规则版 + C5 LLM 增强版排期均可用,降级链完整
- **输入**:B3 产出的 EventService
- **输出**:`/api/schedule/v1/scheduler/recommend` + `/api/schedule/v1/users/{userId}/prefs`
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=RuleSchedulerTest,DeepSchedulerTest,ScoringFunctionTest` 全绿 + P95 < 200ms benchmark
- **Review gate**:`B4.md` 必须 `pass`

### Wave B5:本地日历桥 + iCal 导出
- **Wave ID**:`B5-calendar-ical`
- **任务**:`LocalCalendarBridge` + `MockCalendarAdapter` + `AppleScriptExecutor` + `CalendarController` + `ICalExportService` + `ICalController`
- **依赖 wave**:B3
- **策略**:`serial`(可与 B4 并行,因 B4 改 scheduler 包、B5 改 calendar+ical 包,文件不冲突)
- **目标**:Mac 本地日历 Mock + 真实 AppleScript + iCal 导出全部可调
- **输入**:B3 产出的 EventService
- **输出**:`/api/schedule/v1/internal/local-calendar/sync` + `/api/schedule/v1/events/{id}/ical` + `/api/schedule/v1/events/ical`
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=MockCalendarAdapterTest,AppleScriptExecutorTest,ICalExportServiceTest` 全绿
- **Review gate**:`B5.md` 必须 `pass`

### Wave B6:专注计时器 + 打卡 + Milestone
- **Wave ID**:`B6-focus-checkin`
- **任务**:`FocusSessionEntity` + `FocusService` + `FocusController` + `FocusAbandonedTask` + `CheckinEntity` + `CheckinService` + `FocusMilestonePublisher`
- **依赖 wave**:B3
- **策略**:`serial`(可与 B4/B5 并行)
- **目标**:Focus 生命周期 + 心跳超时 + 连续打卡 + milestone 全通
- **输入**:B3 产出的 EventService + B2 产出的 AuditService
- **输出**:`/api/schedule/v1/events/{id}/focus/*` + SSE `topic=focus-milestone`
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=FocusServiceTest,CheckinServiceTest` 全绿
- **Review gate**:`B6.md` 必须 `pass`

### Wave B7:Emoji + 外部日历
- **Wave ID**:`B7-emoji-external`
- **任务**:`EmojiEntity` + `EmojiService` + `EmojiController` + `ExternalCalendarAdapter` 接口 + 3 实现 + `ExternalSyncService` + `ExternalSyncController`
- **依赖 wave**:B3, B6
- **策略**:`serial`
- **目标**:Emoji 自动分配 + 外部日历 Mock 同步可用
- **输入**:B3 产出的 EventService.create + B6 产出的 focus 会话
- **输出**:`/api/schedule/v1/emoji/*` + `/api/schedule/v1/external-sync/*` + `/api/schedule/v1/events/{id}/sync-retry`
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=EmojiServiceTest,ExternalSyncServiceTest` 全绿
- **Review gate**:`B7.md` 必须 `pass`

### Wave B8:Agent Card 注册 + 服务发现
- **Wave ID**:`B8-registry`
- **任务**:`AgentRegistry` 接口 + `InMemoryAgentRegistry` + `NacosAgentRegistry` + `AgentCardProperties` + `AgentRegistrationTask`
- **依赖 wave**:B3
- **策略**:`serial`(可与 B4~B7 并行)
- **目标**:启动期注册 + 15s 心跳 + discover + 版本管理
- **输入**:B3 产出的 A2AController
- **输出**:`/a2a/v1/agent-card` + `/a2a/v1/agent-card/discover` 真正可调
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=InMemoryAgentRegistryTest,NacosAgentRegistryTest` 全绿
- **Review gate**:`B8.md` 必须 `pass`

### Wave B9:埋点 + AccessLog
- **Wave ID**:`B9-observability`
- **任务**:`MetricsBinder`(4 个 Micrometer)+ `AccessLogService` + `AccessLogBatchWriter` + `/actuator/prometheus` 内网限流 + Prometheus 端点
- **依赖 wave**:B3
- **策略**:`serial`(可与 B4~B8 并行)
- **目标**:4 个 PRD 指标 + AccessLog 批量写入
- **输入**:B3 产出的 A2AController + EventService
- **输出**:`/actuator/prometheus` 暴露 5 个指标 + AccessLog 表批量写入
- **完成标准**:`mvn -o -pl schedule-agent-server test -Dtest=MetricsBinderTest,AccessLogServiceTest` 全绿
- **Review gate**:`B9.md` 必须 `pass`

### Wave B10:集成测试套件
- **Wave ID**:`B10-it`
- **任务**:5 个 IT( `EventFlowIT` / `SchedulerFlowIT` / `FocusFlowIT` / `CalendarFlowIT` / `A2AFlowIT`) 端到端
- **依赖 wave**:B3, B4, B5, B6, B7, B8, B9
- **策略**:`serial`
- **目标**:TestContainers MySQL 8 + 全 controller 端到端跑通
- **输入**:B3~B9 全部产出
- **输出**:`changes/schedule-agent/.integration-receipts/` 5 个 IT 通过报告
- **完成标准**:`mvn -o -pl schedule-agent-server verify` 全绿
- **Review gate**:`B10.md` 必须 `pass`

### Wave B11:客户端 SDK + E2E
- **Wave ID**:`B11-sdk-e2e`
- **任务**:`A2AClient` + `ScheduleTool` + `AgentCardDiscoverer` + `SdkProperties` + `MockCompanionClient` + 6 个 E2E 测试方法
- **依赖 wave**:B10
- **策略**:`serial`
- **目标**:SDK 可被未来 `companion-chat` 引入;E2E 6 主路径全过
- **输入**:B10 全部 IT
- **输出**:`schedule-agent-client-sdk` 模块完整 + 6 个 E2E pass
- **完成标准**:`mvn -o -pl schedule-agent-server verify -Pe2e` 全绿 + 1000 次 P95 < 500ms benchmark
- **Review gate**:`B11.md` 必须 `pass`

### Wave B12:Docker + 文档 + 收尾
- **Wave ID**:`B12-docker-docs`
- **任务**:`Dockerfile`(multi-stage)+ `docker-compose.yml` 增量 schedule-agent service + `.env.example` + 顶级 `README.md` + `CHANGELOG.md` + 仓库根 `README.md` 更新
- **依赖 wave**:B11
- **策略**:`serial`
- **目标**:Docker 镜像可构建 + docker-compose up 启动 + README 链接全 200
- **输入**:B11 全套
- **输出**:`schedule-agent:1.0.0` 镜像 + 文档齐备
- **完成标准**:`docker build .` + `docker run -p 8081:8081 schedule-agent:1.0.0` + `curl /actuator/health` UP
- **Review gate**:`B12.md` 必须 `pass`

## Test Obligations

- **必须先从失败测试开始的行为**:每个 wave 的每个 R-断言 MUST 先写 `*Test.java` 失败用例,再写实现,再 REFACTOR,再 VERIFY(5 阶段 TDD 不可跳过)
- **必需的边界情况**:
  - 加密:错误密钥 / 缺失密钥 / 双密钥过渡 / IV 复用攻击
  - 排期:0 候选 / 全部冲突 / 用户偏好缺失 / RRULE 非法
  - 打卡:心跳超时 / ABANDONED 完成 / 同事件多打卡 / 中断归零
  - LLM:超时 / 配额耗尽 / 输出非 JSON / 缓存命中
  - Mac:AppleScript 权限拒绝 / 不可用平台
  - A2A:幂等键重复 / 状态机非法跳跃 / SSE 超时
- **回归敏感区域**:`EventService.create`(被 C4/C5/C7/C9/C10 全部依赖);`FieldEncryptor`(被 C3/C11 依赖);`AuditService`(被 C3/C6/C8/C11 依赖)

## Execution Mode

- **模式**:`sdd`(full default,本变更为 full workflow)
- **选择理由**:12 能力全量 + 13 批次 + 跨服务架构,远大于 hotfix/tweak 阈值
- **用户 explicit override(仅 inline/batch-inline)**:无(用户 DP-0 选 full)
- **执行计划命令**(DP-3 后由 `ssf execution plan` 落地):`ssf execution plan /Users/jianglu/claudeProject/LLM-note/notebooklm-app/changes/schedule-agent --mode sdd --reason "完整 PRD 全量,13 批次串行,每批独立可验收" --wave B0-scaffold:serial:1.1~1.12 --wave B1-db:serial:2.1~2.8:depends-on=B0 --wave B2-privacy-audit:serial:3.1~3.4:depends-on=B1 --wave B3-events-a2a:serial:4.1~4.18:depends-on=B2 --wave B4-scheduler:serial:5.1~5.7:depends-on=B3 --wave B5-calendar-ical:serial:6.1~6.6:depends-on=B3 --wave B6-focus-checkin:serial:7.1~7.6:depends-on=B3 --wave B7-emoji-external:serial:8.1~8.7:depends-on=B3,B6 --wave B8-registry:serial:9.1~9.5:depends-on=B3 --wave B9-observability:serial:10.1~10.5:depends-on=B3 --wave B10-it:serial:11.1~11.5:depends-on=B3,B4,B5,B6,B7,B8,B9 --wave B11-sdk-e2e:serial:12.1~12.7:depends-on=B10 --wave B12-docker-docs:serial:13.1~13.5:depends-on=B11`
- **允许的修订**:可 `ssf execution revise --mode sdd` 升级/调整 wave 任务清单;不得降级到 `inline` / `batch-inline`
- **计划 revision / artifact hash**:`ssf execution plan` 落地后写入 `<change>/.superpowers/sdd/execution-plan.json`,含 `revision` + `execution_plan_hash`

## Verification Dimensions

| 维度 | 状态 | 发现 |
|------|------|------|
| Completeness | Pending | 12 spec 全部含 SHALL/MUST + WHEN/THEN;72 个 R-断言全部映射;13 wave 全部有产出/输入/标准 |
| Correctness | Pending | `mvn -o verify` 跑通;R1~R102 全绿;P95 性能达标 |
| Coherence | Pending | contract 14 约束与 design 15 决策 + 7 风险一致;tasks 13 批次与 execution waves 一一对应 |

**总体结论**:Pending(待 B0~B12 全部 `pass` review receipt 后填 `pass`)

## Review Gates

- **强制审查点**:每个 Execution Wave 完成后 MUST 记录 `ssf execution review <change-dir> --wave <id> --base <sha> --head <sha> --report <path> --verdict pass|fail`
- **阻塞类别**:`verdict=fail` / 缺失 review receipt / 过期(plan 升级后旧 receipt 清空)
- **收口条件**:B0~B12 全部 `pass` 才能进入 `closing`

## Escalation Rules

- **何时回退到 `specifying`**(任一触发):
  - 14 条 C-约束任一被违反(架构/技术栈/A2A 协议/加密/限流/降级等)
  - 72 个 R-断言任一无法在 spec 对应位置找到原句
  - 13 个 wave 合并 / 拆分导致 contract 与 tasks.md 漂移
  - Scope Fence 任一项被实现(companion-chat 客户端 / 真实 Nacos 部署 / 真实 OAuth / 链路追踪)
- **何时回退到 `bridging`**:无(本变更已 approved-for-build 后不会回退)
- **何时不得继续实现**:
  - `mvn -o compile` 失败超过 1 小时未修
  - 同一 R-断言连续 3 次 `fail` review
  - LLM 配额耗尽持续 > 1 天且降级链仍无法用
  - 加密密钥丢失且无 PREVIOUS 备份

---

## DP-3 评审 Checklist(用户可读版)

> 用户对照此清单逐项确认,无异议即可批准

### A. 范围正确性
- [ ] 12 个能力(C1~C12)全部纳入,无遗漏
- [ ] 6 项 non-goals 明确排除(companion 客户端 / 真实 Nacos / 真实 OAuth / 生产鉴权 / 链路追踪 / 村民日程)
- [ ] 项目位置确定为独立顶级项目,3 模块拆分明确

### B. 规格完整性
- [ ] 12 个 spec 全部含 SHALL/MUST 级断言
- [ ] 每个 Requirement 有 WHEN/THEN Scenario
- [ ] validate 0 错误(已验证)

### C. 设计合理性
- [ ] 15 个设计决策(D1~D15)覆盖架构/接口/依赖/数据
- [ ] 8 个风险(R1~R8)有 mitigation
- [ ] 关键决策有备选方案(rejected 路径有理由)

### D. 执行可落地
- [ ] 13 个 wave 全部有依赖关系(无悬空 wave)
- [ ] 每 wave 有"输入/输出/完成标准"三件套
- [ ] 5 阶段 TDD 不可跳过

### E. 约束可执行
- [ ] 14 条 C-约束无矛盾
- [ ] C1~C7 是 hard rule,违反触发回退 specifying
- [ ] 加密/限流/降级三条核心护栏明确

### F. 测试可验收
- [ ] 72 个 R-断言全部可被单元/集成测试覆盖
- [ ] 6 大主路径 E2E(自然语言建日程 / 冲突检测 / 深度排期 / Mac 日历 / iCal / 打卡)有专测
- [ ] 性能指标 P95 < 500ms(A2A) / 200ms(排期规则)有 benchmark

### G. 集成可演进
- [ ] `schedule-agent-client-sdk` 独立模块,`companion-chat` 后续可通过 `mvn dependency` 复用
- [ ] 路径 `/a2a/v1/*` 含版本号,未来 A2A v2 升级不破坏
- [ ] 16 张表与 `nblm` 物理隔离,可独立备份/迁移

### 用户决策
- [ ] **确认范围** = 完整 PRD 全量 12 能力
- [ ] **确认架构** = 独立顶级项目,3 模块
- [ ] **确认沟通** = 全速推进(每个 wave 完成后自审 + 自动记 review receipt)
- [ ] **无修改建议** 或 已指出需调整的部分
