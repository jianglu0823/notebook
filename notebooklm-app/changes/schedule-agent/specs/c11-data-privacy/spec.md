# Spec: C11 数据隐私（加密 + 审计）

日程数据涉及用户隐私，必须落库前加密 + 全操作审计 + 内部 Token 鉴权。

## ADDED Requirements

### Requirement: 字段级加密

`title` / `description` / `location` 三个字段 MUST 在写入 MySQL 前用 AES-256-GCM 加密：
- 算法：AES-256-GCM（Java `javax.crypto.Cipher` `AES/GCM/NoPadding`）
- 密钥：32 字节 Base64 编码，从 `SCHEDULE_AGENT_ENC_KEY` 环境变量读取
- IV：每条记录独立 12 字节随机 IV，存 `*_iv` 字段（`VARBINARY(12)`）
- 密文：`*_cipher` 字段（`VARBINARY(1024)`）
- 认证标签：128 位 GCM tag，追加在密文末尾

解密失败的行 MUST 返回 `error.code="DECRYPTION_FAILED"` + 触发 `schedule_audit_log` `SECURITY_DECRYPT_FAIL` 事件。

#### Scenario: 加密落库
- WHEN 创建一个 title="团队周会" 的事件
- THEN `SELECT HEX(title_cipher) FROM schedule_event WHERE id=?` 是二进制（不可读中文）

#### Scenario: 解密查询
- WHEN 查询该事件
- THEN 响应体中 `title="团队周会"`（原文）

#### Scenario: 密钥错误无法解密
- WHEN 用错误的 SCHEDULE_AGENT_ENC_KEY 查询
- THEN 抛 AEADBadTagException，返回 `DECRYPTION_FAILED` + audit log `SECURITY_DECRYPT_FAIL`

#### Scenario: 密钥缺失启动失败
- WHEN `SCHEDULE_AGENT_ENC_KEY` 未设置
- THEN 启动期 `IllegalStateException("Encryption key required (SCHEDULE_AGENT_ENC_KEY)")`，应用退出

### Requirement: 密钥轮换

`SCHEDULE_AGENT_ENC_KEY_PREVIOUS` 环境变量 MUST 用于密钥轮换过渡：新写入用 NEW key，解密时优先 NEW key，失败则尝试 PREVIOUS key。轮换完成后，背景任务 MUST 用 NEW key 重加密 PREVIOUS 加密的旧行（`UPDATE ... SET title_cipher=NEW_ENCRYPT(title_plain)`），完成后清空 PREVIOUS。

#### Scenario: 轮换期间双密钥
- WHEN `SCHEDULE_AGENT_ENC_KEY=NEW` + `SCHEDULE_AGENT_ENC_KEY_PREVIOUS=OLD` 都设置
- THEN 新写入用 NEW；旧行解密先用 NEW 失败后用 OLD，均成功

#### Scenario: 轮换完成
- WHEN 背景任务完成重加密
- THEN `PREVIOUS` 环境变量被自动清空（应用启动时检测）

### Requirement: 审计日志

`schedule_audit_log` 表 MUST 记录所有写操作（CREATE / UPDATE / DELETE / FOCUS_START / FOCUS_COMPLETE / EXTERNAL_SYNC_* / CHECKIN_MILESTONE / LLM_CACHE_HIT / SECURITY_DECRYPT_FAIL / REGISTRY_*）。每条 MUST 含 `actor` (userId 或 "system") / `action` (枚举) / `targetType` (event/focus/...) / `targetId` / `before` (JSON, 加密前快照) / `after` (JSON, 加密后快照) / `ts` / `ip` (请求 IP, 可选)。

#### Scenario: CREATE 审计
- WHEN 创建一个事件
- THEN `schedule_audit_log` 新增一行，`action="EVENT_CREATE"`, `after` 含 `title/startTime/endTime/priority`

#### Scenario: UPDATE 审计
- WHEN PATCH 一个事件
- THEN audit log 新增 `EVENT_UPDATE`，`before` 含旧值，`after` 含新值

#### Scenario: DELETE 审计
- WHEN DELETE
- THEN audit log 新增 `EVENT_DELETE`，`before` 含完整事件，`after=null`

#### Scenario: 审计日志不可删
- WHEN 直接调 SQL `DELETE FROM schedule_audit_log`
- THEN 触发器 BEFORE DELETE 抛 `TriggerExecutionException("audit log is append-only")`

#### Scenario: 审计保留期
- WHEN 审计日志超过 365 天
- THEN 后台 task 自动归档到 `schedule_audit_log_archive`（同结构，独立表）；原表保留近 90 天

### Requirement: 内部 Token 鉴权

`/api/schedule/v1/internal/*` 路径 MUST 校验 `X-Schedule-Agent-Token` header，值等于 `schedule-agent.internal.token` 配置项。token 缺失或不匹配 MUST 返回 401 + `error.code="UNAUTHORIZED"`。

#### Scenario: token 缺失
- WHEN POST `/internal/local-calendar/sync` 无 token
- THEN 401 + `error.code="UNAUTHORIZED"`

#### Scenario: token 不匹配
- WHEN token 值与配置不一致
- THEN 401 + `error.message="invalid token"`

#### Scenario: token 一致
- WHEN token 匹配
- THEN 正常处理

#### Scenario: token 通过 env 注入
- WHEN `SCHEDULE_AGENT_INTERNAL_TOKEN` 环境变量设置
- THEN 配置项 `schedule-agent.internal.token` 自动读取 env（@Value("${SCHEDULE_AGENT_INTERNAL_TOKEN}")）

### Requirement: 速率限制

`/api/schedule/v1/*` MUST 做 IP 级速率限制（`schedule-agent.ratelimit.requests-per-minute=60`）。超过 60 req/min/IP MUST 返回 429 + `Retry-After: <秒数>`。

#### Scenario: 正常请求
- WHEN 60 req/min 内
- THEN 全部 200

#### Scenario: 超限
- WHEN 第 61 次请求
- THEN 429 + `Retry-After: 60`
