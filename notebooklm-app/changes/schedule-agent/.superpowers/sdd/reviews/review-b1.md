# B1 Review: MySQL 16 张表 + AuditLog 触发器 + Emoji 种子

**Wave**: B1-db
**Branch**: schedule-agent-build
**Base**: 28789cb
**Head**: f093567
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B1 要求产出:
- [x] Flyway V1__create_schedule_agent_schema.sql
- [x] 16 张表 (schedule_event / attendee / checkin / focus_session / emoji_dict / agent_card / task_artifact / idempotency / external_sync / user_pref / metric_event / audit_log + 4 辅助)
- [x] V2__audit_log_triggers.sql BEFORE DELETE/UPDATE 触发器
- [x] V3__seed_emoji_dict.sql 30 条 INSERT
- [x] TestContainers MySQL 8.0.33 集成测试 base
- [x] 同步更新 infra/mysql/init.sql

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c11 R 审计 | append-only audit log | schedule_audit_log + 触发器抛 SQLSTATE 45000 |
| c3 R 事件 | schedule_event 表 | 软删 is_active + userId 索引 |
| c8 R 打卡 | schedule_checkin | (user_id, event_id, checkin_date) 唯一索引 |
| c8 R 专注 | schedule_focus_session | RUNNING/COMPLETED/ABANDONED 状态字段 |
| c9 R Emoji | schedule_emoji_dict | keyword + emoji + category + priority |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B1 新增 20 测试:TriggerTest 6 + EmojiSeedIT 4 + TableExistsIT 10。

## 关键工程决策

1. 独立 schema schedule_agent:与 nblm 物理隔离。
2. AuditLog 触发器双向:BEFORE DELETE + BEFORE UPDATE 都抛 SQLSTATE 45000。
3. V1/V2/V3 分文件:方便维护。
4. utf8mb4_unicode_ci 与 nblm 对齐。
5. idx_user_active (user_id, is_active, start_time) 覆盖典型查询。
6. TestContainers 静态共享:5s→0s 启动。

## 已知后续

- ALTER TABLE 走 V4 文件,生产不直接改 V1。
- 触发器 MySQL 写法,迁移到 PostgreSQL 需改 trigger function。
