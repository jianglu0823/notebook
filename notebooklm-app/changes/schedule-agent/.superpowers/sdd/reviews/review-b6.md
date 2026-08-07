# B6 Review: 专注计时器 + 打卡 + Milestone

**Wave**: B6-focus-checkin
**Branch**: schedule-agent-build
**Base**: dc1e3ee
**Head**: e647710
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B6 要求产出:
- [x] FocusSessionEntity JPA (status: RUNNING/COMPLETED/ABANDONED)
- [x] CheckinEntity + 唯一索引 (userId, eventId, checkinDate)
- [x] FocusService.start 复用已有 RUNNING 会话
- [x] FocusService.heartbeat 更新 lastHeartbeatAt
- [x] FocusAbandonedTask @Scheduled 1min 心跳超时扫描
- [x] 服务端 Clock 注入:客户端伪造时间被忽略
- [x] FocusService.complete 写 checkin + 计算 streakDays
- [x] CheckinService 连续 7 天 streakDays=7
- [x] 同一事件当天多次打卡去重 (唯一索引)
- [x] milestone 7/30/100/365 触发
- [x] 重复 milestone 去重
- [x] ABANDONED 会话 complete 返回 400
- [x] FocusMilestonePublisher SSE 推送
- [x] FocusController REST

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c8 R start | 返回 sessionId | FocusService.start |
| c8 R 复用 | 已有 RUNNING 复用 | FocusService.start 查询 |
| c8 R heartbeat | 更新 lastHeartbeatAt | FocusService.heartbeat |
| c8 R ABANDONED | 1min 超时扫描 | FocusAbandonedTask @Scheduled |
| c8 R 服务端时钟 | 客户端时间忽略 | Clock 注入 |
| c8 R complete | 写 checkin + streakDays | FocusService.complete |
| c8 R streakDays | 7 天连续 = 7 | CheckinService SQL 查询 |
| c8 R 中断归零 | 检查昨天 checkin | CheckinService streak 计算 |
| c8 R 同事件去重 | 当天多次打卡去重 | 唯一索引 |
| c8 R milestone | 7/30/100/365 | FocusMilestonePublisher |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 168, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B6 新增 26 测试:FocusService 8 + CheckinService 7 + FocusAbandonedTask 4 + FocusMilestonePublisher 4 + FocusController 3。

## 关键工程决策

1. 服务端 Clock 注入:测试用 Clock.fixed,生产用 systemUTC。
2. 1min 心跳超时:@Scheduled fixedRate = 60_000ms。
3. 复用 RUNNING 会话:避免用户重复点开始建多个 session。
4. 唯一索引 (userId, eventId, checkinDate):数据库层去重。
5. streakDays 计算:SQL 查询过去 N 天 count(distinct checkin_date),从今天往回数。
6. milestone 去重:FocusMilestoneLog 表记已触发。
7. SSE 推送 milestone:30min TTL 复用 SseTaskStreamer。

## 已知后续

- 1min 心跳超时硬编码,后续可走用户配置。
- SSE 单机内存,集群需共享 (Redis v2)。
- milestone 阈值 (7/30/100/365) 硬编码,后续可配置。
