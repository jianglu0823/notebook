# B5 Review: 本地日历桥 + iCal 导出

**Wave**: B5-calendar-ical
**Branch**: schedule-agent-build
**Base**: 3e92706
**Head**: dc1e3ee
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B5 要求产出:
- [x] MockCalendarAdapter 写日志 + 追加 ics
- [x] AppleScriptExecutor 包装 ProcessBuilder + osascript
- [x] 权限拒绝返回 403 + PERMISSION_DENIED
- [x] LocalCalendarBridge 异步同步 + audit log
- [x] 重试机制 (3 次后停止)
- [x] ICalExportService biweekly 封装 (VCALENDAR/VEVENT)
- [x] VALARM 默认 15 分钟前
- [x] 多 VALARM ?reminder=30,5
- [x] RRULE 不展开 (透传)
- [x] Content-Disposition filename
- [x] 范围超 90 天返回 400
- [x] CalendarController /internal/local-calendar/sync
- [x] ICalController REST

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c6 R 本地写入 | 事件落本地日历 | LocalCalendarBridge.create |
| c6 R 模式 | MOCK/ICAL/LOCAL 切换 | CalendarMode 枚举 + 工厂 |
| c6 R 权限 | 拒绝 403 | AppleScriptExecutor 捕获 stderr "Not authorized" |
| c6 R 重试 | 3 次后停止 | LocalCalendarBridge 计数 |
| c6 R 失败标记 | syncStatus=FAILED + lastSyncError | LocalCalendarBridge |
| c7 R VCALENDAR | 包含 VEVENT | ICalExportService |
| c7 R VALARM | 默认 15min 前 | ICalExportService.addAlarm |
| c7 R 多 VALARM | ?reminder=30,5 | ICalExportService 多 alarm |
| c7 R RRULE | 不展开 | ICalExportService 透传 |
| c7 R Content-Disposition | filename 规则 | ICalController header |
| c7 R 90 天上限 | 超 90 返回 400 | ICalController 校验 |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 142, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B5 新增 25 测试:MockCalendarAdapter 4 + AppleScript 3 + LocalCalendarBridge 5 + ICalExportService 8 + ICalController 5。

## 关键工程决策

1. MockCalendarAdapter 写日志 + 追加 ics 到 tmp 目录:测试可见。
2. AppleScriptExecutor ProcessBuilder:macOS 专属,Linux 服务器 fallback 到 MOCK。
3. 权限拒绝:stderr 含 "Not authorized" 抛 PermissionDeniedException → 403。
4. 异步同步:@Async + ThreadPoolTaskExecutor,不阻塞主请求。
5. biweekly 库 (0.6.4) 封装 ICalExportService:RFC 5545 标准。
6. 90 天范围硬限制:避免大文件 OOM。
7. CalendarController 走 /internal 前缀,InternalTokenFilter 兜底。

## 已知后续

- Lark/Google 适配器占位抛 UnsupportedOperationException (v1 范围外)。
- 本地日历权限授权需用户在系统设置手动批准。
- ICal 导入 Outlook/Google Calendar 测试覆盖未做。
