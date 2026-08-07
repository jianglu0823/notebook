# B7 Review: Emoji 匹配 + 外部日历同步

**Wave**: B7-emoji-external
**Branch**: schedule-agent-build
**Base**: e647710
**Head**: 27de264
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B7 要求产出:
- [x] EmojiEntity + EmojiService Jaccard 匹配
- [x] 未命中 fallback 📌
- [x] Caffeine 5min TTL 缓存 (P95 < 50ms)
- [x] EventService.create 调用 EmojiService 自动分配
- [x] priority 字段显式覆盖
- [x] MockExternalCalendarAdapter 创建返回 mock-uuid + 读 external-mock.json
- [x] LarkCalendarAdapter + GoogleCalendarAdapter 占位 (UnsupportedOperationException)
- [x] ExternalSyncService @Async + ThreadPoolTaskExecutor
- [x] 失败 audit log + syncStatus=FAILED
- [x] 重试机制 (手动 /sync-retry) + 3 次后停止
- [x] EmojiController + ExternalSyncController

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c9 R Jaccard | 关键词相似度匹配 | EmojiService.match() |
| c9 R 兜底 | 未命中 📌 | EmojiService fallback |
| c9 R 缓存 | 5min TTL P95<50ms | Caffeine |
| c9 R 自动分配 | 创建事件自动 emoji | EventService.create |
| c9 R 显式覆盖 | priority 字段 | EventService 校验 |
| c10 R 适配器 | mock/lark/google | ExternalCalendarAdapter 3 实现 |
| c10 R 异步 | @Async 不阻塞 | ExternalSyncService |
| c10 R 失败标记 | syncStatus=FAILED | ExternalSyncService |
| c10 R 重试 | 3 次后停止 | ExternalSyncService |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 192, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B7 新增 24 测试:EmojiService 6 + EmojiController 3 + MockExternal 4 + Lark/Google 占位 2 + ExternalSyncService 6 + ExternalSyncController 3。

## 关键工程决策

1. Jaccard = |A∩B| / |A∪B|:关键词集合相似度。
2. Caffeine expireAfterWrite 5min:30 条 emoji 字典全量缓存,匹配 P95 < 50ms。
3. 显式覆盖:EventCreateRequest.priority 字段非空时跳过自动 emoji。
4. MockExternalCalendarAdapter 读 external-mock.json:测试数据外置,易维护。
5. Lark/Google 占位抛 UnsupportedOperationException:spec v1 范围外,接口保留。
6. @Async + ThreadPoolTaskExecutor corePoolSize=2:避免阻塞主线程。
7. 失败 audit:log + syncStatus=FAILED + lastSyncError 字段。
8. 重试 3 次:ExternalRetryCounter 字段,超限停。

## 已知后续

- Lark/Google 适配器 v1 不实现,留 v2 接入。
- emoji 字典 30 条偏少,后续扩到 100+。
- 外部同步无 webhook 回调,只能主动拉。
