# B2 Review: AES-256-GCM 加密 + 审计 + 内部 Token + IP 限流

**Wave**: B2-privacy-audit
**Branch**: schedule-agent-build
**Base**: f093567
**Head**: c3081f0
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B2 要求产出:
- [x] FieldEncryptor AES-256-GCM (Cipher AES/GCM/NoPadding) + 密钥缺失 fail-fast
- [x] 双密钥 (NEW + PREVIOUS) 解密优先 NEW
- [x] KeyRotationService @Scheduled 每周日 02:00 重加密
- [x] AuditService 写 schedule_audit_log 统一入口
- [x] InternalTokenFilter 校验 X-Schedule-Agent-Token 头
- [x] RateLimitFilter 60 req/min per-IP, 429
- [x] SecurityConfig 整合 (internal 走 token + 业务放行 + RateLimit)

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c11 R 加密 | title/description/location 落库前 AES-256-GCM | FieldEncryptor.encrypt 透明包装 |
| c11 R 双密钥 | NEW + PREVIOUS 双密钥滚动 | decrypt 优先 NEW |
| c11 R 启动校验 | 缺失 ENC_KEY 启动失败 | @PostConstruct IllegalStateException |
| c11 R 审计 | create/update/delete 触发 audit | AuditService.log |
| c13 R 内部鉴权 | X-Schedule-Agent-Token 校验 | InternalTokenFilter |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B2 新增 27 测试:FieldEncryptor 7 + KeyRotation 4 + AuditService 5 + InternalToken 6 + RateLimit 5。

## 关键工程决策

1. AES-256-GCM 自带 IV:每次随机 IV 前缀 12 字节存密文头。
2. AEADBadTagException 翻译 DECRYPTION_FAILED:不区分密钥错/密文错。
3. 密钥从 env 注入:不写 yml 避免进 git。
4. RateLimit ConcurrentHashMap<String, AtomicInteger>:60s 窗口桶号。
5. InternalTokenFilter 顺序在 RateLimitFilter 之前。
6. KeyRotationService @Scheduled cron 0 0 2 ? * SUN。

## 已知后续

- InternalToken 单 token,多服务调用需 token 池 (v2)。
- RateLimit 固定窗口,后续可换 sliding window。
