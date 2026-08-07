# Tasks: AI 伴侣智能日程与 A2A 协作系统（schedule-agent）

变更目录根：`/Users/jianglu/claudeProject/LLM-note/notebooklm-app/changes/schedule-agent/`
新项目目录根：`/Users/jianglu/claudeProject/LLM-note/schedule-agent/`
包路径根：
- `io.scheduleagent.contract.*`（A2A 协议 DTO）
- `io.scheduleagent.server.*`（主微服务）
- `io.scheduleagent.client.*`（客户端 SDK）

编译/测试：
- 主项目编译：`cd schedule-agent && mvn -o clean compile`
- 主项目测试：`mvn -o test`
- 集成测试（自动跑 TestContainers MySQL）：`mvn -o verify`

## File Structure

**新建（Create）**

顶级项目结构：
- `schedule-agent/pom.xml` — Maven 父 POM（Java 21, Spring Boot 3.3.5 BOM, 子模块管理）
- `schedule-agent/schedule-agent-contract/pom.xml` — contract 模块 POM（零 Spring 依赖）
- `schedule-agent/schedule-agent-server/pom.xml` — server 模块 POM（Spring Boot starter, MySQL, Actuator, Prometheus, biweekly）
- `schedule-agent/schedule-agent-client-sdk/pom.xml` — client-sdk 模块 POM（OkHttp, Jackson, 依赖 contract）
- `schedule-agent/README.md` — 项目使用说明（含 env 变量、profile 切换、集成示例）
- `schedule-agent/Dockerfile` — 多阶段构建（build → jre runtime）

contract 模块（`schedule-agent-contract/src/main/java/io/scheduleagent/contract/`）：
- `a2a/AgentCard.java` / `Skill.java` / `Capabilities.java` — Agent Card DTO
- `a2a/Task.java` / `TaskStatus.java` / `TaskStatusUpdateEvent.java` — 任务状态机
- `a2a/Message.java` / `TextPart.java` / `DataPart.java` / `PartType.java` — 输入消息
- `a2a/Artifact.java` / `ArtifactType.java` — 输出工件
- `a2a/JsonRpcRequest.java` / `JsonRpcResponse.java` / `JsonRpcError.java` — JSON-RPC 2.0 envelope
- `a2a/SkillId.java` (枚举: CREATE_EVENT / SMART_SCHEDULE / DEEP_THINKING_SCHEDULE / ICAL_EXPORT / CHECKIN / EMOJI_RESOLVE)
- `schedule/EventPriority.java` (枚举: P0/P1/P2)
- `schedule/SyncStatus.java` (枚举: PENDING/SYNCED/FAILED/DELETED)
- `schedule/ScheduleEventDto.java` / `ScheduleEventCreateRequest.java` / `ScheduleEventPatchRequest.java` / `ScheduleEventView.java`
- `schedule/SchedulerRecommendRequest.java` / `SchedulerRecommendResponse.java` / `CandidateSlot.java`
- `schedule/CheckinRequest.java` / `CheckinResponse.java`
- `schedule/EmojiResolveRequest.java` / `EmojiResolveResponse.java`
- `schedule/ExternalSyncRequest.java` / `ExternalSyncResponse.java`
- `error/ErrorCode.java` (枚举: INVALID_PARAMS/TASK_NOT_FOUND/CONFLICT/PERMISSION_DENIED/INTERNAL_ERROR/AGENT_UNAVAILABLE/UNAUTHORIZED/DECRYPTION_FAILED/LOCAL_CALENDAR_FAILED/NOT_IMPLEMENTED/RATE_LIMITED)

server 模块（`schedule-agent-server/src/main/java/io/scheduleagent/server/`）：
- `ScheduleAgentApplication.java` — Spring Boot 启动类（@SpringBootApplication, 端口 8081）
- `config/ScheduleAgentProperties.java` — `@ConfigurationProperties("schedule-agent")`，含 calendar.mode / registry.url / llm.model / ratelimit / internal.token
- `config/AsyncConfig.java` — @EnableAsync + ThreadPoolTaskExecutor（adapter executor）
- `config/MetricsConfig.java` — Micrometer Prometheus + 4 个核心指标注册
- `config/CacheConfig.java` — Caffeine 5min TTL cache manager
- `config/SecurityConfig.java` — Spring Security（仅 internal 路径强制 Token，业务路径放行 + IP 限流）
- `config/WebConfig.java` — CORS + ContentNegotiation
- `web/ApiExceptionHandler.java` — @RestControllerAdvice，全局异常 → 统一 ErrorResponse
- `web/AccessLogService.java` — 全路径 AccessLog 异步落 DB
- `web/AccessLogFilter.java` — OncePerRequestFilter，写 AccessLog
- `web/RateLimitFilter.java` — Bucket4j 或简单 ConcurrentHashMap 计数器
- `web/InternalTokenFilter.java` — OncePerRequestFilter，校验 X-Schedule-Agent-Token
- `a2a/A2AController.java` — @RestController，`/a2a/v1/tasks/send` + `/a2a/v1/tasks/sendSubscribe` + `/a2a/v1/agent-card` + `/a2a/v1/agent-card/discover`
- `a2a/TaskDispatcher.java` — 派发到对应 SkillHandler
- `a2a/SkillHandler.java` (接口) — 6 个实现 `CreateEventSkillHandler` / `SmartScheduleSkillHandler` / `DeepThinkingScheduleSkillHandler` / `ICalExportSkillHandler` / `CheckinSkillHandler` / `EmojiResolveSkillHandler`
- `a2a/SseTaskStreamer.java` — `tasks/sendSubscribe` SSE 推送（30min TTL + 心跳注释行）
- `a2a/IdempotencyService.java` — 24h 幂等键去重（内存 Caffeine + 可选 Redis）
- `registry/AgentRegistry.java` (接口) — `InMemoryAgentRegistry` / `NacosAgentRegistry` 2 个实现
- `registry/AgentCardProperties.java` — agentCard 元数据（name/version/skills）
- `registry/AgentRegistrationTask.java` — @Scheduled 启动注册 + 心跳（15s 一次）
- `event/EventEntity.java` / `EventRepository.java` (JPA, soft delete)
- `event/EventService.java` / `EventController.java` (REST `/api/schedule/v1/events`)
- `event/EventMapper.java` — Entity ↔ DTO + 解密转换
- `event/RecurrenceService.java` — RFC 5545 RRULE 解析 + 90 天物化
- `scheduler/RuleScheduler.java` — C4 规则引擎（时间窗切片 + 冲突 + 评分）
- `scheduler/ScoringFunction.java` — 4 维评分（偏好/距离/优先级/历史）
- `scheduler/DeepScheduler.java` — C5 LLM 增强（触发判断 + 缓存 + 降级）
- `scheduler/DashScopeDeepScheduleClient.java` — DashScope SDK 封装，主模型 + 降级
- `scheduler/DeepScheduleCacheService.java` — 24h 缓存（userId+tasksHash+dateRangeHash）
- `scheduler/SchedulerController.java` — REST `/api/schedule/v1/scheduler/recommend`
- `userpref/UserPrefEntity.java` / `UserPrefRepository.java` / `UserPrefService.java` / `UserPrefController.java`
- `calendar/LocalCalendarBridge.java` — C6 主类
- `calendar/CalendarMode.java` (枚举: MOCK/MAC_LOCAL/DISABLED)
- `calendar/MockCalendarAdapter.java` — 写日志 + 追加 `~/.schedule-agent/calendar.ics`
- `calendar/AppleScriptExecutor.java` — `osascript` 调用（mac-local 模式）
- `calendar/AppleScriptExecutorTest.java` — 用 ProcessBuilder 包装执行 + 解析 exit code
- `calendar/CalendarController.java` — REST `/api/schedule/v1/internal/local-calendar/sync`
- `ical/ICalExportService.java` — C7 biweekly 封装（单事件/范围/VALARM/RRULE）
- `ical/ICalController.java` — REST `/api/schedule/v1/events/{id}/ical` + `/api/schedule/v1/events/ical`
- `focus/FocusSessionEntity.java` / `FocusSessionRepository.java` / `FocusService.java` / `FocusController.java` (REST `/api/schedule/v1/events/{id}/focus/start` + `/focus/{sid}/heartbeat` + `/focus/{sid}/complete`)
- `focus/CheckinEntity.java` / `CheckinRepository.java` / `CheckinService.java` — 连续打卡 + milestone 计算
- `focus/FocusAbandonedTask.java` — @Scheduled 1min 一次扫描心跳超时
- `focus/FocusMilestonePublisher.java` — SSE 推送 topic=focus-milestone
- `emoji/EmojiEntity.java` / `EmojiRepository.java` / `EmojiService.java` (Caffeine cache) / `EmojiController.java`
- `external/ExternalCalendarAdapter.java` (接口) / `MockExternalCalendarAdapter.java` / `LarkCalendarAdapter.java` / `GoogleCalendarAdapter.java`
- `external/ExternalSyncService.java` — @Async 包装 + 重试策略
- `external/ExternalSyncController.java` — REST `/api/schedule/v1/external-sync/{eventId}` + `/api/schedule/v1/events/{id}/sync-retry`
- `privacy/FieldEncryptor.java` — AES-256-GCM 加解密 + 双密钥 (NEW + PREVIOUS) + IV 管理
- `privacy/AuditService.java` — append-only 审计日志写入
- `privacy/KeyRotationService.java` — @Scheduled 每周日 02:00 跑密钥轮换重加密
- `metric/MetricsBinder.java` — Micrometer `MeterBinder` 注册 4 个核心指标
- `metric/AccessLogEntity.java` / `AccessLogRepository.java` / `AccessLogService.java` / `AccessLogBatchWriter.java` — 5s/1000条批量 flush
- `audit/AuditLogEntity.java` / `AuditLogRepository.java` — append-only 含 BEFORE DELETE 触发器
- `audit/AuditLogArchiver.java` — @Scheduled 每日 03:00 归档 365 天前记录

server 资源（`schedule-agent-server/src/main/resources/`）：
- `application.yml` — 默认配置（dev profile）
- `application-prod.yml` — prod 覆盖（registry.url=nacos, calendar.mode=mock）
- `application-mac-local.yml` — mac-local 覆盖（calendar.mode=mac-local）
- `db/migration/V1__create_schedule_agent_schema.sql` — 12 张表 + 索引 + 触发器
- `db/migration/V2__seed_emoji_dict.sql` — 30+ Emoji 种子
- `emoji-dict.json` — Emoji 字典 JSON（Spring 启动时 load 到 Caffeine）

client-sdk 模块（`schedule-agent-client-sdk/src/main/java/io/scheduleagent/client/`）：
- `A2AClient.java` — OkHttp 封装 + JSON-RPC 2.0 + 重试 + 降级
- `ScheduleTool.java` — 包装 A2AClient，提供 `createEvent` / `recommendSchedule` / `exportICal` 三个高层 API
- `AgentCardDiscoverer.java` — 服务发现（`/a2a/v1/agent-card/discover?skill=...`）
- `SdkProperties.java` — 客户端配置（baseUrl / token / timeout）
- `exception/A2AException.java` / `A2AFallbackException.java`

server 测试（`schedule-agent-server/src/test/java/io/scheduleagent/server/`）：
- `event/EventServiceTest.java` — CRUD + 软删 + 跨用户隔离
- `event/EventControllerTest.java` — MockMvc
- `scheduler/RuleSchedulerTest.java` — 评分 + 冲突 + 替代方案
- `scheduler/DeepSchedulerTest.java` — Mock DashScope，验证降级链路
- `calendar/MockCalendarAdapterTest.java` — 验证 calendar.ics 内容
- `calendar/AppleScriptExecutorTest.java` — 用真实 `osascript -e 'return 1+1'` 验证退出码解析
- `ical/ICalExportServiceTest.java` — 验证 VCALENDAR / VEVENT / VALARM 块
- `focus/FocusServiceTest.java` — 心跳超时 + ABANDONED + 连续统计
- `emoji/EmojiServiceTest.java` — Jaccard 匹配 + 缓存命中
- `external/ExternalSyncServiceTest.java` — 异步 + 重试 + 失败标记
- `privacy/FieldEncryptorTest.java` — AES-256-GCM + 双密钥 + 错误密钥抛异常
- `privacy/AuditServiceTest.java` — 审计写入 + 不可删验证
- `metric/AccessLogServiceTest.java` — 批量 flush
- `integration/MysqlIntegrationTestBase.java` — TestContainers MySQL 8 共享基类
- `integration/EventFlowIT.java` — 创建 → 查询 → 更新 → 删除 + 加密落库
- `integration/SchedulerFlowIT.java` — C4 规则 + 冲突检测
- `integration/FocusFlowIT.java` — start → heartbeat → complete
- `e2e/A2AEndToEndTest.java` — 启动完整 Spring Boot 进程 + `MockCompanionClient` 验证 A2A 契约
- `e2e/MockCompanionClient.java` — 模拟 companion HTTP 调用方

client-sdk 测试（`schedule-agent-client-sdk/src/test/java/io/scheduleagent/client/`）：
- `A2AClientTest.java` — MockWebServer 验证 HTTP 协议 + 重试
- `ScheduleToolTest.java` — 验证 3 个高层 API 透传

**修改（Modify）**
- `infra/mysql/init.sql` — 追加 `CREATE DATABASE schedule_agent` + 12 张表 DDL（与 V1__ 一致，作为"基线"备份）
- `docker-compose.yml` — 新增 `schedule-agent` service 条目（端口 8081, env 注入）

## Interfaces

跨批次契约（exact types）：

```java
// ============== contract 模块 ==============
// （全部 POJO，含 @JsonProperty / Lombok @Value / @Builder）

// A2A 协议
record AgentCard(String name, String description, String url, String version,
                 Capabilities capabilities, List<Skill> skills);
record Skill(String id, String name, String description, Map<String, Object> inputSchema);
record Capabilities(boolean streaming, boolean pushNotifications);

record Task(String id, TaskStatus status, List<Message> inputMessages,
             List<Artifact> artifacts);
enum TaskStatus { SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, FAILED }
record TaskStatusUpdateEvent(String taskId, TaskStatus status, double progress,
                              List<Artifact> artifacts, String timestamp);
record Message(String role, List<Object> parts); // parts: List<TextPart|DataPart>
record TextPart(String type, String text) { type = "text"; }
record DataPart(String type, Map<String, Object> data) { type = "data"; }
record Artifact(String type, String name, Map<String, Object> data);

record JsonRpcRequest(String jsonrpc, String id, String method, Map<String, Object> params);
record JsonRpcResponse(String jsonrpc, String id, Task result, JsonRpcError error);

// Schedule 业务
enum EventPriority { P0, P1, P2 }
enum SyncStatus { PENDING, SYNCED, FAILED, DELETED }
record ScheduleEventDto(String id, String title, String description, Instant startTime,
                        Instant endTime, EventPriority priority, String location,
                        List<String> attendees, String recurrence, String emoji,
                        SyncStatus syncStatus, String idempotencyKey);
record ScheduleEventCreateRequest(String title, String description, Instant startTime,
                                   Instant endTime, EventPriority priority, String location,
                                   List<String> attendees, String recurrence, String emoji,
                                   String idempotencyKey);
record ScheduleEventPatchRequest(...); // 11 字段全部 Optional
record ScheduleEventView(...); // DTO + DecryptedString fields

record SchedulerRecommendRequest(List<RecommendTask> tasks, DateRange dateRange,
                                  List<String> existingEventIds, boolean deepThinking);
record RecommendTask(String title, int durationMinutes, EventPriority priority,
                     String preferredWindow);
record DateRange(Instant from, Instant to);
record SchedulerRecommendResponse(List<CandidateSlot> recommendations,
                                   List<CandidateSlot> alternatives, boolean usedLLM);
record CandidateSlot(Instant start, Instant end, int score, String reason,
                      String conflictWith);

record CheckinRequest(String reflection);
record CheckinResponse(int streakDays, boolean milestoneReached, Integer milestone);

record EmojiResolveRequest(String title, String description);
record EmojiResolveResponse(String emoji, String matchedKeyword, double score);

record ExternalSyncRequest(String provider, ScheduleEventDto event);
record ExternalSyncResponse(String provider, String externalId, Instant lastSyncAt,
                             SyncStatus syncStatus, String lastError);

// Error
enum ErrorCode { INVALID_PARAMS, TASK_NOT_FOUND, CONFLICT, PERMISSION_DENIED,
                 INTERNAL_ERROR, AGENT_UNAVAILABLE, UNAUTHORIZED, DECRYPTION_FAILED,
                 LOCAL_CALENDAR_FAILED, NOT_IMPLEMENTED, RATE_LIMITED,
                 INCOMPATIBLE_PROTOCOL_VERSION, MISSING_AGENT_CARD_FIELD }
record ErrorResponse(ErrorCode code, String message, Map<String, Object> details);

// ============== server 模块 ==============
// 服务接口
interface EventService {
    ScheduleEventDto create(Long userId, ScheduleEventCreateRequest req);
    ScheduleEventView get(Long userId, String eventId);
    List<ScheduleEventView> list(Long userId, Instant from, Instant to, EventPriority priority, String keyword);
    ScheduleEventDto patch(Long userId, String eventId, ScheduleEventPatchRequest req);
    void delete(Long userId, String eventId);
}

interface RuleScheduler {
    SchedulerRecommendResponse recommend(Long userId, SchedulerRecommendRequest req);
}

interface DeepScheduler {
    SchedulerRecommendResponse recommend(Long userId, SchedulerRecommendRequest req); // 内部 fallback 到 RuleScheduler
}

interface LocalCalendarBridge {
    String create(ScheduleEventDto event);
    boolean update(String localEventId, ScheduleEventDto event);
    boolean delete(String localEventId);
}

interface FieldEncryptor {
    byte[] encrypt(String plain, byte[] iv);
    String decrypt(byte[] cipher, byte[] iv);
    byte[] generateIv();
}

interface AgentRegistry {
    void register(AgentCard card, String url);
    void heartbeat(AgentCard card);
    List<AgentCard> discoverBySkill(String skillId);
}

// 控制器接口
@RestController
class A2AController {
    @PostMapping("/a2a/v1/tasks/send")
    ResponseEntity<JsonRpcResponse> sendTask(@RequestBody JsonRpcRequest req);
    @PostMapping(value = "/a2a/v1/tasks/sendSubscribe", produces = "text/event-stream")
    SseEmitter sendSubscribe(@RequestBody JsonRpcRequest req);
    @GetMapping("/a2a/v1/agent-card")
    AgentCard getAgentCard();
    @GetMapping("/a2a/v1/agent-card/discover")
    List<AgentCard> discover(@RequestParam String skill);
}

@RestController
class EventController {
    @PostMapping("/api/schedule/v1/events") ResponseEntity<ScheduleEventDto> create(...);
    @GetMapping("/api/schedule/v1/events/{id}") ScheduleEventView get(...);
    @GetMapping("/api/schedule/v1/events") List<ScheduleEventView> list(...);
    @PatchMapping("/api/schedule/v1/events/{id}") ScheduleEventDto patch(...);
    @DeleteMapping("/api/schedule/v1/events/{id}") ResponseEntity<Void> delete(...);
}

@RestController
class SchedulerController {
    @PostMapping("/api/schedule/v1/scheduler/recommend") SchedulerRecommendResponse recommend(...);
}

// ============== client-sdk 模块 ==============
class A2AClient {
    A2AClient(SdkProperties props);
    Task sendTask(String taskType, Message input, String idempotencyKey);
    SseEmitter subscribeTask(String taskType, Message input); // 包装 SSE
    AgentCard getAgentCard();
    List<AgentCard> discover(String skill);
}

class ScheduleTool {
    ScheduleTool(A2AClient client);
    ScheduleEventDto createEvent(String title, Instant start, Instant end, ...);
    SchedulerRecommendResponse recommendSchedule(List<RecommendTask> tasks, DateRange range);
    String exportICal(String eventId); // 返回 iCal 文本
}
```

## TDD Phases (per task)

每个任务 5 阶段 TDD:
1. **RED**: 写失败测试（单元/集成）
2. **GREEN**: 最小实现让测试通过
3. **REFACTOR**: 清理（提取方法、改名、消除重复）
4. **VERIFY**: 跑 `mvn -o test` 该模块全绿
5. **COMMIT**: 单次原子 commit（`feat(Cx): ...` 或 `fix(Cx): ...`）

## Tasks

### B0. 项目骨架 + contract 模块初始化
**目标**：建立顶级 Maven 项目结构 + 3 模块骨架 + contract 模块首批 A2A 协议类

**步骤**：
1. RED: `schedule-agent-contract/src/test/java/.../AgentCardTest.java` — 序列化/反序列化
2. GREEN: 创建 AgentCard / Skill / Capabilities / Task / TaskStatus / Message / TextPart / DataPart / Artifact / JsonRpcRequest / JsonRpcResponse / JsonRpcError
3. RED: `TaskStatusTest.java` — 状态机流转合法性
4. GREEN: 状态机合法性 + IllegalStateException
5. 创建 ErrorCode 枚举 + ErrorResponse record
6. 创建 ScheduleEventDto + Create/Patch/View + SchedulerRecommendRequest/Response + CandidateSlot
7. 创建 CheckinRequest/Response + EmojiResolveRequest/Response + ExternalSyncRequest/Response
8. 创建 EventPriority / SyncStatus 枚举
9. 创建顶级 pom.xml（Java 21, Spring Boot 3.3.5 BOM, 子模块）
10. 创建 contract/pom.xml（零依赖）+ server/pom.xml（spring-boot-starter-web/jpa/actuator）+ client-sdk/pom.xml（OkHttp + Jackson, 依赖 contract）
11. VERIFY: `cd schedule-agent && mvn -o -pl schedule-agent-contract compile test-compile`
12. COMMIT: `feat(scaffold): 顶级 Maven 项目骨架 + contract 模块 A2A 协议 DTO`

**Interfaces**:
- 产出：上述全部 contract 类（POJO/record）
- 消费：B1+ 所有 batch 引用

**Depends on**: 无

### B1. MySQL 迁移 + 12 张表 + AuditLog 触发器
**目标**：建库 + 12 张表 + 索引 + 触发器 + 种子数据

**步骤**：
1. RED: `integration/MysqlIntegrationTestBase.java` — TestContainers MySQL 8.0.33
2. GREEN: TestContainers 启动 MySQL，验证连接
3. 创建 `db/migration/V1__create_schedule_agent_schema.sql`：
   - `CREATE DATABASE IF NOT EXISTS schedule_agent DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci`
   - 12 张表 DDL（schedule_event / schedule_attendee / schedule_checkin / schedule_focus_session / schedule_emoji_dict / schedule_agent_card / schedule_task_artifact / schedule_idempotency / schedule_external_sync / schedule_user_pref / schedule_metric_event / schedule_audit_log）
   - 主键 + 唯一索引 + 外键 + 字符集声明
4. RED: `audit/TriggerTest.java` — 尝试 DELETE FROM schedule_audit_log 抛异常
5. GREEN: 写 BEFORE DELETE 触发器（MySQL `BEFORE DELETE ON schedule_audit_log` 抛 SIGNAL SQLSTATE '45000'）
6. 创建 `db/migration/V2__seed_emoji_dict.sql`（30 条 INSERT ... ON DUPLICATE KEY UPDATE）
7. RED: `EmojiSeedIT.java` — 跑 V2 验证 `SELECT COUNT(*) >= 30`
8. GREEN: 跑通
9. 同步更新 `infra/mysql/init.sql`（追加 CREATE DATABASE + 12 张表 DDL）
10. VERIFY: `mvn -o -pl schedule-agent-server verify -Dtest=MysqlIntegrationTestBase`
11. COMMIT: `feat(db): 12 张表 DDL + 审计触发器 + Emoji 种子`

**Interfaces**:
- 产出：12 张表 + 触发器 + emoji-dict.json
- 消费：B2+ 所有 repository 引用

**Depends on**: B0

### B2. 隐私 + 审计（C11）+ 内部 Token 鉴权
**目标**：FieldEncryptor + AuditService + InternalTokenFilter + RateLimitFilter

**步骤**：
1. RED: `privacy/FieldEncryptorTest.java` — encrypt + decrypt round-trip
2. GREEN: AES-256-GCM 实现（Cipher `AES/GCM/NoPadding`）
3. RED: 错误密钥 decrypt 抛 AEADBadTagException
4. GREEN: 捕获异常 + 返回 DECRYPTION_FAILED
5. RED: 双密钥（NEW + PREVIOUS）解密优先 NEW
6. GREEN: 完整双密钥逻辑
7. RED: 密钥缺失启动失败
8. GREEN: `@PostConstruct` 检查 + IllegalStateException
9. RED: `audit/AuditServiceTest.java` — 创建/更新/删除事件触发 audit log
10. GREEN: AuditService 写 schedule_audit_log
11. RED: `web/InternalTokenFilterTest.java` — token 缺失/不匹配返回 401
12. GREEN: InternalTokenFilter 实现
13. RED: `web/RateLimitFilterTest.java` — 60 req/min 内 OK，第 61 次 429
14. GREEN: RateLimitFilter + ConcurrentHashMap<String, AtomicInteger> per-IP
15. RED: `privacy/KeyRotationServiceTest.java` — 模拟双密钥环境，验证重加密逻辑
16. GREEN: KeyRotationService @Scheduled 每周日 02:00 跑
17. 集成 SecurityConfig 配置 internal 路径走 InternalTokenFilter，业务路径放行 + RateLimitFilter
18. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=FieldEncryptorTest,AuditServiceTest,InternalTokenFilterTest,RateLimitFilterTest`
19. COMMIT: `feat(privacy): AES-256-GCM 加密 + 审计 + 内部 Token + IP 限流`

**Interfaces**:
- 产出：FieldEncryptor / AuditService / KeyRotationService / InternalTokenFilter / RateLimitFilter
- 消费：B3+ 所有写操作引用 FieldEncryptor.encrypt

**Depends on**: B1

### B3. 事件 CRUD + A2A 协议服务端（C3 + C1）
**目标**：Event CRUD 完整 + A2A `tasks/send` + `tasks/sendSubscribe` + `agent-card` 端点

**步骤**：
1. RED: `event/EventEntityTest.java` — 软删 + status='deleted' 验证
2. GREEN: EventEntity (JPA, soft delete) + EventRepository
3. RED: `event/EventServiceTest.java` — create 加密落库 + get 解密返回
4. GREEN: EventService + EventMapper（FieldEncryptor 注入）
5. RED: 跨用户访问返回 404（不暴露存在性）
6. GREEN: userId 隔离
7. RED: PATCH 改 startTime 触发冲突检测
8. GREEN: 滑动窗口冲突扫描
9. RED: DELETE 软删 + 关联表 hidden=true
10. GREEN: 软删 + 关联更新
11. RED: `recurrence/RecurrenceServiceTest.java` — RFC 5545 RRULE 解析
12. GREEN: RecurrenceService 物化 90 天实例
13. RED: `a2a/A2AControllerTest.java` — POST `/a2a/v1/tasks/send` 自然语言建日程
14. GREEN: A2AController + TaskDispatcher + 6 个 SkillHandler 占位
15. RED: DataPart 跳过 NLU
16. GREEN: TextPart 走 NLU, DataPart 跳过
17. RED: 必填参数缺失返回 400
18. GREEN: 校验
19. RED: A2A 协议版本不匹配
20. GREEN: 检查 jsonrpc 字段
21. RED: `a2a/SseTaskStreamerTest.java` — `tasks/sendSubscribe` 推送 submitted → working → completed
22. GREEN: SseTaskStreamer（30min TTL + 心跳）
23. RED: 冲突触发 input-required
24. GREEN: 推流 + 保持连接
25. RED: 状态机非法跳跃抛 IllegalStateException
26. GREEN: 状态机校验
27. RED: `a2a/IdempotencyServiceTest.java` — 同 key 24h 内去重
28. GREEN: IdempotencyService（Caffeine cache）
29. RED: 错误响应含 error.code + error.message
30. GREEN: ApiExceptionHandler
31. 集成 EventController + A2AController
32. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=EventServiceTest,A2AControllerTest,SseTaskStreamerTest,IdempotencyServiceTest`
33. COMMIT: `feat(events+a2a): Event CRUD + A2A 协议服务端 + 状态机 + 幂等键`

**Interfaces**:
- 产出：EventService / EventController / A2AController / TaskDispatcher / SkillHandler(6 个) / SseTaskStreamer / IdempotencyService / RecurrenceService
- 消费：B4+ 调用 EventService.create / get

**Depends on**: B2

### B4. 智能排期规则引擎 + 深度思考（C4 + C5）
**目标**：RuleScheduler 规则引擎 + DeepScheduler LLM 增强 + 触发判断 + 降级

**步骤**：
1. RED: `scheduler/ScoringFunctionTest.java` — 4 维评分（偏好 40 + 距离 20 + 优先级 20 + 历史 20）
2. GREEN: ScoringFunction
3. RED: `scheduler/RuleSchedulerTest.java` — 单任务推荐 Top-3
4. GREEN: RuleScheduler 时间窗切片
5. RED: 冲突检测（已有事件 14:00-15:00，候选 14:30-15:30 跳过）
6. GREEN: 滑动窗口冲突
7. RED: 0 候选时段返回空
8. GREEN: 边界检查
9. RED: 用户偏好应用（上午不排会）
10. GREEN: UserPrefService 集成
11. RED: 评分 reason 中文说明
12. GREEN: reason 拼接前 2 维度
13. RED: 替代方案 conflict_with
14. GREEN: 邻近时段滑动
15. RED: 性能 P95 < 200ms
16. GREEN: JMH 基准测试
17. RED: `scheduler/DeepSchedulerTest.java` — 触发条件 3 任务含 P0
18. GREEN: DeepScheduler 触发判断
19. RED: 关键字触发
20. GREEN: title/description 包含"深度思考" / "帮我规划" 触发
21. RED: 低分触发（C4 top score < 60）
22. GREEN: 自动 fallback 到 LLM
23. RED: LLM Prompt 注入（system + user JSON）
24. GREEN: DashScopeDeepScheduleClient
25. RED: LLM 输出解析失败降级到 C4
26. GREEN: 解析 + fallback
27. RED: P0/P1/P2 优先级切片
28. GREEN: 解析 timeBlocks
29. RED: 10s 超时降级
30. GREEN: Future.cancel + fallback
31. RED: 24h 缓存命中
32. GREEN: DeepScheduleCacheService
33. RED: 主模型失败降级 glm-4-flash
34. GREEN: 模型降级链
35. 集成 SchedulerController
36. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=RuleSchedulerTest,DeepSchedulerTest`
37. COMMIT: `feat(scheduler): C4 规则引擎 + C5 深度思考 LLM 增强 + 降级 + 缓存`

**Interfaces**:
- 产出：RuleScheduler / DeepScheduler / ScoringFunction / DashScopeDeepScheduleClient / DeepScheduleCacheService
- 消费：B5+ 调用 recommend 接口

**Depends on**: B3

### B5. 本地日历桥 + iCal 导出（C6 + C7）
**目标**：LocalCalendarBridge + 3 种 CalendarMode + iCal 导出（单事件 + 范围 + VALARM + RRULE）

**步骤**：
1. RED: `calendar/MockCalendarAdapterTest.java` — 验证 calendar.ics 内容
2. GREEN: MockCalendarAdapter 写日志 + 追加 ics
3. RED: `calendar/AppleScriptExecutorTest.java` — 用 `osascript -e 'return 1+1'` 验证退出码解析
4. GREEN: AppleScriptExecutor 包装 ProcessBuilder
5. RED: 权限拒绝返回 403 + PERMISSION_DENIED
6. GREEN: 捕获 stderr "Not authorized" → 403
7. RED: CalendarController `/internal/local-calendar/sync` token 校验
8. GREEN: InternalTokenFilter 已配，加 @RequestMapping("/internal")
9. RED: 同步失败标记 FAILED + lastSyncError
10. GREEN: 异步更新 + audit log
11. RED: 重试机制（3 次后停止）
12. GREEN: 计数 + 限流
13. RED: `ical/ICalExportServiceTest.java` — VCALENDAR 包含 VEVENT
14. GREEN: ICalExportService biweekly 封装
15. RED: VALARM 默认 15 分钟前
16. GREEN: VALARM 块
17. RED: 多 VALARM `?reminder=30,5`
18. GREEN: 多个 VAlarm
19. RED: RRULE 不展开
20. GREEN: 解析 RRULE
21. RED: Content-Disposition header
22. GREEN: filename 规则
23. RED: 范围超 90 天返回 400
24. GREEN: 边界检查
25. 集成 ICalController
26. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=MockCalendarAdapterTest,ICalExportServiceTest`
27. COMMIT: `feat(calendar+ical): Mac 本地日历桥 (mock + AppleScript) + iCal 导出`

**Interfaces**:
- 产出：LocalCalendarBridge / MockCalendarAdapter / AppleScriptExecutor / ICalExportService
- 消费：B6+ 调用 LocalCalendarBridge.create

**Depends on**: B3

### B6. 专注计时器 + 打卡 + Milestone（C8）
**目标**：Focus 会话生命周期 + 心跳 + ABANDONED + 连续打卡 + milestone

**步骤**：
1. RED: `focus/FocusServiceTest.java` — start 返回 sessionId
2. GREEN: FocusService.start
3. RED: 复用已有 RUNNING 会话
4. GREEN: 查询 + 复用
5. RED: 事件已结束返回 400
6. GREEN: 边界检查
7. RED: heartbeat 更新 lastHeartbeatAt
8. GREEN: 写库
9. RED: 心跳超时自动 ABANDONED
10. GREEN: FocusAbandonedTask @Scheduled 1min 扫描
11. RED: 客户端伪造时间被服务端忽略
12. GREEN: 服务端用本地时钟
13. RED: complete 写 checkin + 计算 streakDays
14. GREEN: CheckinService
15. RED: 连续 7 天 streakDays=7
16. GREEN: SQL 查询过去 N 天计数
17. RED: 中断归零
18. GREEN: 检查昨天是否有 checkin
19. RED: 同一事件当天多次打卡去重
20. GREEN: 唯一索引 (userId, eventId, checkinDate)
21. RED: milestone 7/30/100/365
22. GREEN: 触发 + audit log
23. RED: 重复 milestone 去重
24. GREEN: 记录已触发的 milestone
25. RED: ABANDONED 会话 complete 返回 400
26. GREEN: 校验状态
27. RED: 屏蔽迟到提醒
28. GREEN: 推送服务查询 RUNNING 状态
29. 集成 FocusController
30. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=FocusServiceTest,CheckinServiceTest`
31. COMMIT: `feat(focus): 专注计时器 + 心跳超时 + 连续打卡 + milestone SSE`

**Interfaces**:
- 产出：FocusService / CheckinService / FocusAbandonedTask / FocusMilestonePublisher
- 消费：B7+ 验证 FocusService.complete

**Depends on**: B3

### B7. Emoji 匹配 + 外部日历同步（C9 + C10）
**目标**：EmojiService + ExternalCalendarAdapter 3 实现 + 异步同步

**步骤**：
1. RED: `emoji/EmojiServiceTest.java` — Jaccard 匹配命中
2. GREEN: EmojiService 解析
3. RED: 未命中 fallback 📌
4. GREEN: 兜底
5. RED: 缓存命中 P95 < 50ms
6. GREEN: Caffeine 5min TTL
7. RED: 创建事件自动分配 emoji
8. GREEN: EventService.create 调用 EmojiService
9. RED: 显式覆盖
10. GREEN: priority 字段
11. RED: `external/MockExternalCalendarAdapterTest.java` — 创建返回 mock-uuid
12. GREEN: MockExternalCalendarAdapter
13. RED: list 返回最近 100 条
14. GREEN: 读 external-mock.json
15. RED: LarkCalendarAdapter 抛 UnsupportedOperationException
16. GREEN: 占位实现
17. RED: GoogleCalendarAdapter 抛 UnsupportedOperationException
18. GREEN: 占位实现
19. RED: @Async 不阻塞主线程
20. GREEN: ExternalSyncService @Async + ThreadPoolTaskExecutor
21. RED: 失败 audit log + syncStatus=FAILED
22. GREEN: 异常捕获 + 状态更新
23. RED: 重试机制（手动 /sync-retry）
24. GREEN: 计数 + 限制
25. RED: 重试 3 次后停止
26. GREEN: 边界检查
27. 集成 EmojiController + ExternalSyncController
28. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=EmojiServiceTest,ExternalSyncServiceTest`
29. COMMIT: `feat(emoji+external): Emoji 字典匹配 + 外部日历适配器 (mock + 占位)`

**Interfaces**:
- 产出：EmojiService / ExternalCalendarAdapter (3 实现) / ExternalSyncService
- 消费：B11 验证 ScheduleTool 调用 externalAdapter

**Depends on**: B3, B6

### B8. Agent Card 注册 + 服务发现（C2）
**目标**：AgentRegistry 2 实现 + 启动注册 + 心跳 + discover

**步骤**：
1. RED: `registry/InMemoryAgentRegistryTest.java` — register + discoverBySkill
2. GREEN: InMemoryAgentRegistry (ConcurrentHashMap)
3. RED: 启动自动注册
4. GREEN: AgentRegistrationTask @PostConstruct
5. RED: 15s 心跳
6. GREEN: @Scheduled fixedRate
7. RED: NacosAgentRegistry POST `/nacos/v1/ns/instance`
8. GREEN: RestTemplate 调用
9. RED: 连续 3 次心跳失败重注册
10. GREEN: 失败计数
11. RED: Nacos 启动失败降级 in-memory
12. GREEN: try-catch + WARN
13. RED: Nacos 认证 username/password
14. GREEN: 配置项读取 env
15. RED: discover 按 skill 过滤 + 延迟升序
16. GREEN: discover 端点
17. RED: MAJOR 版本清空注册表
18. GREEN: 版本号解析
19. RED: MINOR 版本移除旧版
20. GREEN: 增量注册
21. 集成 A2AController `/a2a/v1/agent-card` + `/a2a/v1/agent-card/discover`
22. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=InMemoryAgentRegistryTest,NacosAgentRegistryTest`
23. COMMIT: `feat(registry): Agent Card 注册 + 服务发现 (in-memory + Nacos)`

**Interfaces**:
- 产出：AgentRegistry / InMemoryAgentRegistry / NacosAgentRegistry / AgentRegistrationTask
- 消费：B11 客户端 SDK 调用 discover

**Depends on**: B3

### B9. 埋点 + AccessLog（C12）
**目标**：4 个核心 Micrometer 指标 + AccessLog 批量写入

**步骤**：
1. RED: `metric/MetricsBinderTest.java` — 注册 4 个指标
2. GREEN: MetricsBinder + 4 个 MeterBinder
3. RED: A2A 延迟 Timer 记录
4. GREEN: A2AContler AOP 切片记录
5. RED: 周活 Gauge 每周一 00:00 刷新
6. GREEN: @Scheduled cron
7. RED: 确认率 counter
8. GREEN: 事件 confirm
9. RED: 打卡完成率 counter
10. GREEN: focus/complete +1
11. RED: 轮次 Distribution
12. GREEN: input-required 续传计数
13. RED: `/actuator/prometheus` 限制内网 IP
14. GREEN: Tomcat Valve 或 Spring Security
15. RED: AccessLog 批量 flush（5s/1000 条）
16. GREEN: AccessLogBatchWriter
17. RED: 高 QPS 100+ 走批量
18. GREEN: Scheduled flush
19. 集成 MetricsConfig + WebConfig
20. VERIFY: `mvn -o -pl schedule-agent-server test -Dtest=MetricsBinderTest,AccessLogServiceTest`
21. COMMIT: `feat(observability): 4 个 Micrometer 指标 + AccessLog 批量写入 + Prometheus 限内网`

**Interfaces**:
- 产出：MetricsBinder / AccessLogService / AccessLogBatchWriter
- 消费：B10 E2E 验证指标

**Depends on**: B3

### B10. 集成测试套件（IT）
**目标**：5 个核心 IT 端到端验证 SQL + JSON + 控制器

**步骤**：
1. RED: `integration/EventFlowIT.java` — 创建 → 查询 → 更新 → 删除 + 加密落库
2. GREEN: 跑通
3. RED: `integration/SchedulerFlowIT.java` — C4 规则 + 冲突检测
4. GREEN: 跑通
5. RED: `integration/FocusFlowIT.java` — start → heartbeat → complete
6. GREEN: 跑通
7. RED: `integration/CalendarFlowIT.java` — Mock 本地日历写入 + ics 验证
8. GREEN: 跑通
9. RED: `integration/A2AFlowIT.java` — `tasks/send` + 自然语言建日程
10. GREEN: 跑通
11. VERIFY: `mvn -o -pl schedule-agent-server verify`
12. COMMIT: `test(integration): 5 个核心 IT 端到端覆盖`

**Interfaces**:
- 产出：5 个 IT 类
- 消费：B11 E2E 复用

**Depends on**: B3, B4, B5, B6, B7, B8, B9

### B11. 客户端 SDK + E2E 测试
**目标**：A2AClient + ScheduleTool + MockCompanionClient + E2E 测试

**步骤**：
1. RED: `client-sdk/src/test/java/.../A2AClientTest.java` — MockWebServer
2. GREEN: A2AClient OkHttp + JSON-RPC
3. RED: 重试机制（3 次指数退避）
4. GREEN: retry config
5. RED: 降级策略（schedule-agent 不可用 → 抛 A2AFallbackException）
6. GREEN: 异常类
7. RED: `ScheduleToolTest.java` — createEvent 透传
8. GREEN: ScheduleTool
9. RED: `AgentCardDiscovererTest.java` — discover 按 skill
10. GREEN: AgentCardDiscoverer
11. RED: `e2e/A2AEndToEndTest.java` — 启动 Spring Boot + MockCompanionClient 调 sendTask
12. GREEN: E2E 跑通
13. RED: 6 大主路径覆盖（自然语言建日程 / 冲突检测 / 深度排期 / Mac 日历写入 / iCal 导出 / 打卡）
14. GREEN: 6 个 E2E 测试方法
15. RED: 跨进程延迟 P95 < 500ms
16. GREEN: 1000 次请求 benchmark
17. VERIFY: `mvn -o -pl schedule-agent-server verify -Pe2e`
18. COMMIT: `feat(sdk+e2e): A2AClient + ScheduleTool + 6 大主路径 E2E 覆盖`

**Interfaces**:
- 产出：A2AClient / ScheduleTool / AgentCardDiscoverer / 6 个 E2E 测试
- 消费：未来 `companion-chat` 引入此 SDK

**Depends on**: B10

### B12. Docker + 文档 + 收尾
**目标**：Dockerfile + docker-compose 增量 + README + 配置 .env.example

**步骤**：
1. 写 `schedule-agent/Dockerfile`（multi-stage：maven build → eclipse-temurin:21-jre runtime）
2. 写 `schedule-agent/.dockerignore`
3. 写 `schedule-agent/.env.example`（所有 SCHEDULE_AGENT_* 环境变量）
4. 更新 `docker-compose.yml` 增量 schedule-agent service（端口 8081, env_file）
5. RED: docker build 镜像
6. GREEN: 镜像构建成功
7. RED: docker-compose up schedule-agent 启动
8. GREEN: `/actuator/health` 返回 UP
9. 写 `schedule-agent/README.md`：
   - 项目简介 + 12 能力列表
   - 快速开始（env / mvn / docker-compose）
   - profile 切换（dev / prod / mac-local）
   - 12 个能力 curl 示例
   - companion-chat 集成示例（client-sdk 引入）
   - 故障排查（密钥丢失 / LLM 配额 / Mac 权限）
10. RED: README 链接全部 200
11. GREEN: 链接校对
12. 写 `schedule-agent/CHANGELOG.md`（v1.0.0 首发）
13. 更新仓库根 `README.md`（新增 schedule-agent 一节）
14. VERIFY: `docker build .` + `docker run -p 8081:8081 schedule-agent:1.0.0` + curl `/actuator/health`
15. COMMIT: `docs+docker: Dockerfile + docker-compose 增量 + README + CHANGELOG`

**Interfaces**:
- 产出：Dockerfile / docker-compose 增量 / README / CHANGELOG
- 消费：运维 / 新成员 onboarding

**Depends on**: B11

## 批次完成状态(全 13 批次已实现并通过 review receipt)

- [x] **B0** 项目骨架 + contract 模块初始化 — commit `28789cb`
- [x] **B1** MySQL 迁移 + 12 张表 + AuditLog 触发器 — commit `f093567`
- [x] **B2** 隐私 + 审计(C11)+ 内部 Token 鉴权 — commit `c3081f0`
- [x] **B3** 事件 CRUD + A2A 协议服务端(C3 + C1)— commit `0f4d395`
- [x] **B4** 智能排期规则引擎 + 深度思考(C4 + C5)— commit `3e92706`
- [x] **B5** 本地日历桥 + iCal 导出(C6 + C7)— commit `dc1e3ee`
- [x] **B6** 专注计时器 + 打卡 + Milestone(C8)— commit `e647710`
- [x] **B7** Emoji 匹配 + 外部日历同步(C9 + C10)— commit `27de264`
- [x] **B8** Agent Card 注册 + 服务发现(C2)— commit `88f02dd`
- [x] **B9** 埋点 + AccessLog(C12)— commit `d150894`
- [x] **B10** 集成测试套件(IT)— commit `e646d13`
- [x] **B11** 客户端 SDK + E2E 测试 — commit `628d5e7`
- [x] **B12** Docker + 文档 + 收尾 — commit `1eef702`

测试覆盖(末态):contract 15 + server 229 + client-sdk 13 = 257 单元;集成 10;`mvn -o test` 全绿。

