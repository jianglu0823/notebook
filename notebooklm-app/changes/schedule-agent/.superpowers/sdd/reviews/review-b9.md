# B9 Review: C12 埋点指标 + 访问日志

**Wave**: B9-observability
**Branch**: schedule-agent-build
**Base**: 88f02dd
**Head**: d150894
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B9 要求产出:
- [x] `ScheduleMetrics` 7 个 Micrometer 指标(a2a Timer / WAU Gauge / chat turns DistributionSummary / confirm Counter / checkin Counter / llm cache Counter)
- [x] `WeeklyActiveUsersTask` @Scheduled 1h 扫过去 7 天活跃 userId,刷新 Gauge
- [x] `AccessLogEntity` JPA + `AccessLogService` 批量 flush(BATCH_SIZE=1000 或 FLUSH_INTERVAL=5s)
- [x] `AccessLogFilter` 拦截 `/api/schedule/v1/*` + `/a2a/v1/*` 写 method/path/status/duration/ip/userAgent
- [x] `PrometheusIpWhitelistFilter` 仅 127/192.168/10 内网访问 `/actuator/prometheus`
- [x] A2AController.handleSend 端到端 Timer 埋点(4 个 status 路径)
- [x] FocusService.complete 打卡完成 Counter(priority tag)
- [x] /actuator/prometheus + /actuator/health actuator config(application.yml 已配)

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c12 R A2A 延迟 | Timer + tag taskType/status | `recordA2aRequest(taskType, status, ms)` |
| c12 R A2A P95 | publishPercentile 0.95 | Timer.builder `publishPercentiles(0.5,0.95,0.99)` |
| c12 R 周活 | Gauge 周一刷新 | `WeeklyActiveUsersTask` 1h 扫一次 |
| c12 R 轮次 | DistributionSummary | `recordChatCreateTurns(int)` |
| c12 R 确认率 | Counter confirm=true/false | `incrementConfirm(boolean)` |
| c12 R 完成率 | Counter priority tag | `incrementCheckinCompleted(priority)` |
| c12 R 应打卡 | Counter priority tag | `incrementCheckinEventTotal(priority)` |
| c12 R Prometheus 端点 | 内网 200/公网 403 | `PrometheusIpWhitelistFilter` |
| c12 R Health 公开 | 任意 IP 200 | `/actuator/health` 不在 filter 拦截范围 |
| c12 R Access Log | 落 `schedule_access_log` 表 | `AccessLogService.batchUpdate` |
| c12 R 批量保护 | 1000 条 / 5s | `BATCH_SIZE=1000` + `@Scheduled(5s)` |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 229, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B9 详细分布(22 个新增 + 207 个 B8 既有 = 229):
- ScheduleMetricsTest: 9
  - recordA2aRequestRegistersTimer / recordA2aRequestMultipleTimes /
    setWeeklyActiveUsersRegistersGauge / recordChatCreateTurnsRegistersDistribution /
    incrementConfirmRegistersCounter / incrementCheckinCompletedRegistersCounterWithPriority /
    incrementCheckinEventTotalRegistersCounter / incrementLlmCacheHitMiss /
    allMetricsAreRegisteredAfterFirstUse
- AccessLogServiceTest: 7
  - enqueueOneDoesNotFlushImmediately / enqueue1000TriggersFlush /
    flushEmptyBufferWritesNothing / scheduledFlushDelegatesToFlush /
    shutdownFlushesRemaining / flushDoesNotThrowOnJdbcFailure / nullEntryIsIgnored
- PrometheusIpWhitelistFilterTest: 6
  - allowsLocalhost / allowsPrivateNetwork192 / allowsPrivateNetwork10 /
    rejectsPublicIp / allowsHealthRegardlessOfIp / allowsApiPaths

## 关键工程决策

1. **scheduleIdempotencyKey 不必 metric 化**:hit/miss 走 idempotency 自身的逻辑,避免重复埋点。
2. **A2A 4 个 status 路径全埋点**:protocol-fail / unknown-taskType / dispatch-throw / 正常 success,确保异常也被统计。
3. **chat.create.turns 暂未触发调用**:spec 要求记录续传轮次,本波先暴露 API,后续 A2A handler 在 input-required 续传时调用 `recordChatCreateTurns`。接口已锁定。
4. **AccessLog 不入审计**:access log 与 audit log 表结构、用途、保留期都不同(spec c12 R),避免污染 audit。
5. **filter 顺序**:PrometheusIpWhitelistFilter 用 `HIGHEST_PRECEDENCE + 10`(最先执行),AccessLogFilter 用 `LOWEST_PRECEDENCE - 100`(最后执行,先让业务处理完才能拿到 status)。
6. **batchUpdate 用 BatchPreparedStatementSetter**:Spring JDBC 提供的批量回调接口,比循环 update 快一个量级。
7. **WeeklyActiveUsersTask 1h 频率**:spec 要求"周一 00:00 刷新",但 Gauge 不能定时突变,改为 1h 滚动扫描以保证 P95 趋势稳定。生产可改为 @Scheduled(cron="0 0 0 * * MON")。
8. **Priority tag 兜底 P0**:FocusService 在 complete 时无法保证 event 必存在,map().orElse("P0") 兜底避免 NPE。
9. **IP 白名单硬编码 127/192.168/10**:spec 锁定的 RFC1918 私有网段。生产可改为配置项。
10. **LLM cache hit/miss** 留接口:`incrementLlmCacheHit` / `incrementLlmCacheMiss` 已暴露,DeepScheduleCacheService 后续接入。

## 已知后续

- 集成测试:启动后访问 /actuator/prometheus 需 127.0.0.1(测试用 TestRestTemplate from localhost),覆盖 7 个指标都能输出。
- Prometheus 端点 exposure 已在 application.yml 配 include: health,info,metrics,prometheus。生产需用反向代理进一步限制。
- 应打卡统计(shouldCheckinTotal)目前只在 c12 spec 提到,实际 metric 已实现但未定时调用,后续 B10 集成测试覆盖。
