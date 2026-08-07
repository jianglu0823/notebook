# Design: AI 伴侣智能日程与 A2A 协作系统（schedule-agent）

## Context

### 现状
- `LLM-note` 已落地 4 大应用域：QA 问答、播客、小红书工作台、智能体小镇 + 伴侣聊天。
- 现有 `notebooklm-app`（Spring Boot 3.3.5, Java 21, AgentScope 2.0.0-RC4）单体包 `io.llmnote.*`。
- `companion-chat` 变更处于 `approved-for-build`（DP-3 已批准，尚未开始实现）。
- LLM 工厂 `io.llmnote.llm.ChatModelFactory` 合并 GLM 免费 + Qwen 付费，提供 `pricing()` / `normalize()` / `streamText()` / `streamTextWithFallback()`；`reasoningModel()` 默认 `glm-z1-flash`。
- 鉴权 `io.llmnote.auth.AuthFilter`（cookie/JWT）；WebSocket 握手鉴权 `VoiceHandshakeInterceptor`。
- 前端 `notebooklm-app/src/main/resources/static/index.html` 单文件 SPA。
- 数据库 `ddl-auto:none`，所有新表/列需手工 ALTER；既有 DDL 在 `infra/mysql/init.sql`。
- 主项目端口 8080，gateway/dashboard 网关调度。

### 约束
- **Java 21 + Spring Boot 3.3.5** 锁版本（与 `notebooklm-app` 同步升级风险最小）。
- **独立进程**：本期不引入 Nacos 真实部署，dev profile 默认 in-memory 注册表，prod profile 通过 `schedule-agent.registry.url` 切到 `nacos://...` URL。
- **跨进程 A2A**：必须走真实 HTTP/SSE 跨进程，不能简化成同进程方法调用（PRD 协议要求 + 未来可独立扩缩容）。
- **A2A 协议版本**：本期固定 `jsonrpc="2.0"`，路径 `/a2a/v1/*`，预留未来 A2A v2 升级空间。
- **数据隐私**：title/description/location 必走 AES-256-GCM 加密；密钥 32 字节 Base64 走环境变量；密钥缺失启动失败。
- **Mac 本地日历**：Mock 默认 + `mac-local` profile 启用 AppleScript；非 Mac 平台启动 `mac-local` 失败时降级到 mock（不让开发机卡住）。
- **iCal 库选型**：biweekly 0.6.4（Java 生态 RFC 5545 事实标准）；biweekly 比手写减少 80% 维护成本，比 ical4j-v0xx 体积小 50%。
- **LLM 客户端**：DashScope SDK（与 `notebooklm-app` 同款），主模型 `glm-4.6` → 降级 `glm-4-flash` → 降级 C4 规则。
- **既有代码零侵入**：`schedule-agent` 是顶级独立项目，不 `mvn dependency` 引用 `notebooklm-app` 任何 class，只复用模式（鉴权 / 软删 / 限流）。
- **测试隔离**：12 张表建在独立 schema `schedule_agent`，与 `nblm` 数据库物理隔离；integration test 用 TestContainers MySQL 8 镜像。

### Stakeholders
- 用户（最终客户：希望通过 AI 伴侣对话建日程、打卡、看 iCal）。
- 伴侣 Agent 客户端（companion-chat 后续版本，本期不实现）。
- 第三方日历平台（飞书/Google/Apple，本期仅留接口 + Mock）。
- 平台运维（监控：4 个核心 Micrometer 指标）。
- 安全审计（合规：加密落库 + append-only 审计 + 内部 Token）。

## Goals
1. **A2A 协议服务端**完整可调（6 个 skill + Agent Card + JSON-RPC 2.0 + SSE 流式），P95 延迟 < 500ms。
2. **12 个能力**全部落地（CRUD / 排期 / 深度思考 / Mac 日历 / iCal / 打卡 / Emoji / 外部钩子 / 隐私 / 埋点 / Agent Card / 客户端 SDK）。
3. **客户端 SDK 独立模块**，可被 `companion-chat` 后续版本通过 `mvn dependency` 复用，无需重写 A2A 客户端。
4. **数据零明文**：title/description/location 全加密落库，密钥通过 env 注入，启动 fail-fast。
5. **降级链路完整**：LLM 超时/失败 → C4 规则；schedule-agent 不可用 → companion 提示"已记录在备忘录"。
6. **集成测试**端到端覆盖 6 大主路径：自然语言建日程 / 冲突检测 / 深度排期 / Mac 日历写入 / iCal 导出 / 打卡。

## Decisions

### D1. 独立顶级 Maven 项目，3 模块拆分
**Choice**：`/Users/jianglu/claudeProject/LLM-note/schedule-agent/` 顶级项目，含 3 个 Maven 子模块：
- `schedule-agent-contract`（共享 DTO + A2A 协议 Java 类 + 枚举，零依赖）
- `schedule-agent-server`（主微服务 Spring Boot 3.3.5，端口 8081）
- `schedule-agent-client-sdk`（Java SDK，封装 A2AClient + ScheduleTool + retry + fallback）

**Rationale**：与 `companion-chat` 后续版本彻底解耦；客户端 SDK 可独立发版到 Maven 仓库；contract 模块 server/sdk 共用，类型零漂移。

**Alternatives**：
- ❌ 单模块（Maven 简单但 client-sdk 复用需要 source-jar 复制）
- ❌ 直接放 `notebooklm-app` 同级包（污染主项目，未来拆分会疼）

### D2. A2A 协议实现：直接 HTTP + SSE，不引入 A2A 框架
**Choice**：手写 `A2AController`（JSON-RPC 2.0 dispatch）+ `SseEmitter` 推送 `TaskStatusUpdateEvent`，不引入 AgentScope A2A SDK 之外的额外框架。

**Rationale**：AgentScope A2A SDK 仍在 RC 阶段（2.0.0-RC4），API 不稳；手写实现 200 行内可控；契约稳定后再考虑升级。

**Alternatives**：
- ❌ 用 AgentScope A2A SDK（API 变更风险）
- ❌ 用 grpc（PRD 明确要求 HTTP/SSE）

### D3. 数据库 schema 隔离，独立 12 张表
**Choice**：12 张表建在独立 MySQL schema `schedule_agent`，与 `nblm` 数据库物理隔离。

**Rationale**：schedule_agent 是独立服务、独立部署、独立演进；schema 隔离避免与主项目数据字典冲突；备份/迁移可独立做。

**Alternatives**：
- ❌ 放主 schema `nblm`（表空间污染，未来拆分会疼）
- ❌ 用 PostgreSQL（与 PRD 一致但引入新数据库类型，运维成本翻倍）

### D4. iCal 库选 biweekly 0.6.4
**Choice**：使用 `maven:net.sf.biweekly:biweekly:0.6.4`。

**Rationale**：Java 生态 RFC 5545 事实标准（ICAL4J 0.x 已停更）；体积小（~200KB）；API 简单（`ICalCalendar` / `VEvent` / `VAlarm`）；支持 VALARM / RRULE / UTF-8。

**Alternatives**：
- ❌ 手写 RFC 5545（容易踩 `\\r\\n` 换行 / `\\n` 单换行兼容坑）
- ❌ ical4j 0.x（停更 + Apache 1.x 协议）

### D5. 加密方案：JDK 自带 AES-256-GCM
**Choice**：用 `javax.crypto.Cipher` `AES/GCM/NoPadding`，密钥 32 字节从 `SCHEDULE_AGENT_ENC_KEY` 读。

**Rationale**：JDK 自带无新依赖；AES-GCM 是 AEAD（认证加密）防止密文被篡改；性能足够（Spring Boot 3 默认 HotSpot JIT 优化）。

**Alternatives**：
- ❌ Tink（Google 库，但增加 ~1MB 依赖）
- ❌ Bouncy Castle（更全但体积大，且与 JDK 默认安全提供者优先级冲突）

### D6. LLM 客户端：复用 DashScope SDK，不引用 ChatModelFactory 类
**Choice**：直接 `mvn dependency` `com.alibaba:dashscope-sdk-java`（同 `notebooklm-app` 版本），自己写 `DashScopeDeepScheduleClient` 封装 `Generation.call()`。

**Rationale**：`schedule-agent` 独立进程，不能 `import io.llmnote.llm.ChatModelFactory`（反向依赖）；复制一份 ~100 行的 LLM 客户端比强耦合更易演进。

**Alternatives**：
- ❌ 抽 `llmnote-common` 共享模块（为 1 个客户端类抽公共包，over-engineer）
- ❌ 通过 HTTP 调 `notebooklm-app` 的 `/api/llm/*`（跨服务调用延迟高，且耦合主服务）

### D7. Mac 本地日历：Mock 默认 + profile 切换
**Choice**：`schedule-agent.calendar.mode=mock|mac-local|disabled`，默认 `mock`；`mac-local` 调 `osascript`。

**Rationale**：dev/CI 环境无 macOS；mock 让测试与开发无需 macOS；真实 Mac 用户的 `application-mac-local.yml` 单独启用 AppleScript；非 Mac 平台 `mac-local` 启动失败时降级到 mock。

**Alternatives**：
- ❌ 强制 mac-local（CI 上跑不起来）
- ❌ EventKit via JNA（Java 17+ 已废弃 JNA 强类型 binding，开发维护成本高）

### D8. Emoji 字典：30+ 种子 + Caffeine 缓存
**Choice**：30 条种子在 `data-emoji-dict.sql`，启动时 `INSERT ... ON DUPLICATE KEY UPDATE`；运行时 `Caffeine` 5min TTL 缓存。

**Rationale**：30 条覆盖 PRD 列举的常用场景（会议/健身/吃饭/学习/医疗等）；Caffeine 5min TTL 让字典变更 5min 内生效，不需要重启应用。

**Alternatives**：
- ❌ 100+ 全量（启动慢 + 内存大 + 命中率边际递减）
- ❌ 调 LLM 解析（一次 emoji 解析要 1s+，过度设计）

### D9. 客户端 SDK：包路径 `io.scheduleagent.client`
**Choice**：`schedule-agent-client-sdk` 模块导出 `io.scheduleagent.client.A2AClient` + `io.scheduleagent.client.ScheduleTool`。

**Rationale**：包路径不与 `io.llmnote.*` 冲突（避免 `companion-chat` 引入时类路径混乱）；SDK API 极简（`new A2AClient(url)` + `client.sendTask(...)` + `new ScheduleTool(client)`）。

**Alternatives**：
- ❌ 包路径用 `io.llmnote.companion.schedule`（污染主项目命名空间）

### D10. 鉴权：内部 Token + 跳过 /api/schedule/v1 外部鉴权
**Choice**：`/api/schedule/v1/internal/*` 强制 `X-Schedule-Agent-Token`；`/api/schedule/v1/*`（业务路径）和 `/a2a/v1/*`（A2A）本期不强制鉴权（仅 IP 限流）。

**Rationale**：MVP 阶段 `companion-chat` 等调用方通过内网直连，不强制鉴权减少摩擦；internal 路径必须 Token 防止外部触发本地日历写操作；后续版本可加 mTLS / OIDC（v2 范围）。

**Alternatives**：
- ❌ 全路径强制 JWT（companion-chat 集成时增加鉴权联调）
- ❌ 全路径放行（internal 路径被外部触发会有安全风险）

### D11. 排期算法 v1：纯规则 + 评分函数，不依赖 LLM
**Choice**：C4 规则引擎独立可用，时间窗切片 + 冲突检测 + 4 维评分（偏好 40 + 距离 20 + 优先级 20 + 历史 20）。

**Rationale**：规则版 200ms 内 P95；LLM 增强是 C5 的"按需"；规则版失败时 fallback 路径已经能给出"可接受"结果。

**Alternatives**：
- ❌ 默认走 LLM（每次排期 1s+ 延迟，PRD 目标 P95 < 500ms 难达）
- ❌ 调第三方排期 API（如 Calendly API，超范围）

### D12. SSE 实现：Spring `SseEmitter` 长连接，30min TTL
**Choice**：`tasks/sendSubscribe` 用 `SseEmitter(timeout=30min)`；心跳事件 `:` 注释行保活；客户端断开时 `onCompletion` 清理。

**Rationale**：Spring 内置无需新依赖；30min 覆盖深度思考最长场景；心跳注释行防代理超时。

**Alternatives**：
- ❌ WebSocket（PRD 明确 SSE）
- ❌ 长轮询（延迟高 + server 资源消耗大）

### D13. 部署形态：本地 dev 用 Maven 启动，prod 用 Docker
**Choice**：dev 直接 `mvn spring-boot:run`；prod 写 `Dockerfile` + `docker-compose.yml` 增量（不修改现有 compose）；CI 跑 `mvn verify`。

**Rationale**：与 `notebooklm-app` 同款部署模式；Docker 镜像 `schedule-agent:1.0.0`；环境变量通过 `.env` 注入。

**Alternatives**：
- ❌ 直接打 jar 不写 Docker（部署脚本每个环境各写一份）
- ❌ 用 K8s manifest（当前项目未上 K8s）

### D14. 测试策略：单元 + 集成 + 端到端三层
**Choice**：
- 单元测试（`src/test/java/.../*Test.java`）：Mock 所有外部依赖，覆盖 service 层逻辑
- 集成测试（`src/test/java/.../integration/*IT.java`）：TestContainers MySQL 8 + MockWebServer，验证 controller + repository 端到端
- 端到端测试（`src/test/java/.../e2e/*E2E.java`）：启动完整 Spring Boot 进程 + mock companion HTTP client，验证 A2A 契约

**Rationale**：单元测试保证逻辑正确；集成测试保证 SQL/JSON 序列化正确；E2E 测试保证 A2A 契约对 companion 端透明。

**Alternatives**：
- ❌ 全 Mock 单元测试（不能验证 SQL / 序列化）
- ❌ 全 E2E（CI 慢，定位问题难）

### D15. 监控：Micrometer + Prometheus + AccessLog
**Choice**：4 个核心 Micrometer Timer/Counter/Distribution + 1 个 Gauge（周活）+ 全路径 AccessLog 落 DB；Prometheus 端点限制内网 + 公网 403。

**Rationale**：4 个指标直接对应 PRD 第五章的 4 个目标值（周活 / 轮次 / 确认率 / 打卡率 + 延迟）；AccessLog 便于排查；公网 403 避免指标泄漏。

**Alternatives**：
- ❌ 用 Sleuth + Zipkin（当前项目未上分布式追踪）
- ❌ 全公网 Prometheus（指标含 IP / userAgent，有合规风险）

## Risks And Trade-Offs

### R1. A2A 协议未来升级
**Risk**：A2A 协议可能在 v2 改变 `Task` / `Message` / `Artifact` schema，本期手写实现需要兼容。
**Mitigation**：路径 `/a2a/v1/*` 含版本号；A2A 协议 Java 类集中在 `schedule-agent-contract` 模块，未来 v2 仅需新增 `a2a/v2/*` 路径 + 新 contract 类，不影响 server 主代码。

### R2. companion-chat 集成时间点
**Risk**：`companion-chat` 仍处于 `approved-for-build` 未执行，schedule-agent 集成测试时无真实 companion 调用方。
**Mitigation**：`schedule-agent-server/src/test/java/.../e2e/` 内置 `MockCompanionClient`（模拟 companion HTTP 调用方）做端到端；companion-chat 后续合入时仅需替换为真实 `schedule-agent-client-sdk` 的 `A2AClient` 实例。

### R3. Mac 本地日历 AppleScript 权限
**Risk**：macOS 14+ 对 AppleScript 自动化权限收紧，未授权时 `osascript` 静默失败。
**Mitigation**：明确捕获 `osascript` 退出码 1 + stderr "Not authorized"，返回 403 + `PERMISSION_DENIED` 错误码 + 引导文案；mock 模式无需权限（默认）。

### R4. 加密密钥丢失
**Risk**：`SCHEDULE_AGENT_ENC_KEY` 丢失则历史事件全部不可解密。
**Mitigation**：密钥缺失时启动 fail-fast（防止"用错密钥继续写"）；文档化密钥备份流程；密钥轮换支持 `PREVIOUS` 过渡。

### R5. LLM 配额耗尽
**Risk**：DashScope 余额耗尽时 C5 深度排期全部失败。
**Mitigation**：默认 10s 超时 + 自动降级到 C4 规则 + 响应头 `X-Schedule-Fallback-Reason: LLM_QUOTA_EXHAUSTED` 提示前端展示降级原因；audit log `DEEP_SCHEDULE_FALLBACK` 事件供监控告警。

### R6. SSE 长连接资源占用
**Risk**：1000 个客户端同时订阅 `tasks/sendSubscribe` 会占 1000 个长连接。
**Mitigation**：30min 硬超时 + 客户端心跳保活 + Nginx `proxy_read_timeout 1800s`；监控 gauge `schedule.a2a.sse.active_connections` 报警阈值 800。

### R7. 限流误伤
**Risk**：IP 限流 60 req/min 可能在企业 NAT 下误伤正常用户。
**Mitigation**：v1 仅 IP 限流（简单优先）；v2 引入 userId 维度限流；当前阈值 60 req/min 在企业内部环境可控。

### R8. DDL 跨环境一致性
**Risk**：`migration.sql` 在 dev 通过但 prod MySQL 版本/字符集不一致时执行失败。
**Mitigation**：`migration.sql` 显式 `SET NAMES utf8mb4` + `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`；CI 跑 MySQL 8 容器执行验证。

## Capabilities Mapping

| Capability ID | Spec | Key Class |
|---------------|------|-----------|
| C1 | c1-a2a-protocol-server | `A2AController`, `TaskDispatcher` |
| C2 | c2-agent-card-registration | `AgentRegistry`, `NacosAgentRegistry` |
| C3 | c3-schedule-event-crud | `EventService`, `EventController` |
| C4 | c4-smart-scheduler | `RuleScheduler`, `ScoringFunction` |
| C5 | c5-deep-thinking-scheduling | `DeepScheduler`, `DashScopeDeepScheduleClient` |
| C6 | c6-local-calendar-bridge | `LocalCalendarBridge`, `AppleScriptExecutor` |
| C7 | c7-ical-export | `ICalExportService` |
| C8 | c8-focus-timer-checkin | `FocusService`, `CheckinService` |
| C9 | c9-emoji-mapping | `EmojiService` |
| C10 | c10-external-calendar-sync | `ExternalCalendarAdapter` + 3 impls |
| C11 | c11-data-privacy | `FieldEncryptor`, `AuditService`, `InternalTokenFilter` |
| C12 | c12-observability | `MetricsConfig`, `AccessLogService` |
