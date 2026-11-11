# W3 Review Report

**Wave**: W3 — 智谱水印回归测试
**Branch**: town-resident-interactions
**Base SHA**: 3db406e (W2)
**Head SHA**: 4bf88a0 (W3)
**Reviewer**: self

## Spec Compliance

| Spec | Requirement | Implementation | Status |
|------|-------------|----------------|--------|
| 04:watermark-regression-test | generateImage body MUST have watermark_enabled:false | assertThat(json.get("watermark_enabled").asBoolean()).isFalse() | PASS |
| 04 | submitVideo(prompt) same | separate test | PASS |
| 04 | submitVideo(prompt, size, withAudio) same | separate test asserts all 3 fields | PASS |
| 04 | mock HttpClient, no real network | @Mock HttpClient,反射替换 internal http 字段 | PASS |
| 04 | no ZHIPU_API_KEY required | test-key 注入 NotebookLmProperties | PASS |
| 04 | 故意删 watermark_enabled → 测试挂 | sed 删除后跑测试 → "watermark_enabled 字段缺失" | PASS (verified) |
| 04 | 改成 true → 测试挂 | (未单独跑,但 assertFalse() 在 true 时也会挂) | PASS by design |

## Code Quality

- 不需要改 ZhipuMediaClient 实际代码(spec 明确 out of scope)
- 反射只用在测试 setUp 中,把 private final `http` 字段替换为 mock;生产代码完全不动
- 用 JDK 标准 Flow.Publisher API 订阅 bodyPublisher,无需反射 JDK 内部类,跨 JDK 11/17/21 通用
- 反向验证已用 sed 实测:删除 watermark_enabled 行后,CI 必挂,提示"watermark_enabled 字段缺失"
- 4 个测试覆盖 spec 全部场景 + 1 个端到端 url 解析 smoke test

## Issues Found

- 无 critical
- Minor: 反向验证"误改成 true"的 case 未单独 sed 验证,但 `isFalse()` 断言本身在 true 时必然失败(逻辑保证)

## Test Evidence

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- ZhipuMediaClientTest
[INFO] BUILD SUCCESS
```

Reverse validation:
```
# sed 删除 watermark_enabled 行:
[ERROR] Tests run: 4, Failures: 1, Errors: 2, Skipped: 0
[ERROR] generateImage_requestBodyMustContainWatermarkDisabledFalse -- [watermark_enabled 字段缺失]

# 恢复后:
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Verdict

PASS — spec MUST requirements all implemented + reverse-validated. Ready for W4.
