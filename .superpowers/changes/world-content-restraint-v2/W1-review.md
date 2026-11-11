# W1 Review — world-content-restraint-v2

**Wave**: W1 (F1 + F2 + F3, serial)
**Base**: `3caabf6` (carry-over)
**Head**: `90c5bd0` (F3 commit)
**Date**: 2026-08-18
**Result**: PASS

---

## 覆盖度(spec compliance)

### F1: Zhipu CogView 水印(specs/01-zhipu-cogview-watermark)

- `watermark_enabled:false` 必传 — PASS, `ZhipuMediaClient.java:59` `body.put("watermark_enabled", false)`
- `watermark:false` 冗余兜底 — PASS, `ZhipuMediaClient.java:60` `body.put("watermark", false)`
- 单测校验两个字段都带 — PASS, `ZhipuMediaClientWatermarkTest.generateImage_bodyHasWatermarkDisabled`(4 测全绿)
- 视频 submit 同样带 `watermark_enabled:false` — PASS, `ZhipuMediaClient.java:93` `body.put("watermark_enabled", false)`
- `submitVideo_withSizeAndAudio` body 字段 — PASS, `ZhipuMediaClientWatermarkTest.submitVideo_withSizeAndAudio_bodyHasAllFields`

部署验证(curl 直连智谱)受 auto classifier 限制,无法在沙盒执行 — 见 Risk。

### F2: WorldSimEngine 守门(specs/02-worldsim-media-gate)

- `WorldSettings.mediaEnabled=false` 时 4 个 produce 都返 null — PASS, `WorldSimEngineMediaGateTest.mediaDisabled_blocksAllProduces`(1 测绿)
- `maybeCreate` 主守门 — PASS, `WorldSimEngine.maybeCreate` 顶部检查 `mediaEnabled`
- `produceArtwork` 二次短路 — PASS, `WorldSimEngine.produceArtwork` 顶部 `if (!mediaEnabled()) return null;`
- `produceVideo` 二次短路(不写占位行) — PASS, `WorldSimEngine.produceVideo` 同上
- `produce` (novel/song) 二次短路 — PASS, `WorldSimEngine.produce` 同上
- 构造器注入 WorldSettingsService — PASS, `WorldSimEngine` 字段 + Lombok 14 参构造

### F3: Forum 兜底 video_series 配额(specs/03-forum-video-quota)

- `WorldSettings.forumVideoMinPerDay` 字段(默认 1,非空) — PASS, `WorldSettings.java` 新增字段 + Javadoc
- `WorldSettingsService.update` 接受新参数(0~20 clamp) — PASS, `WorldSettingsService.update` 新增第 7 参;`WorldSettingsServiceTest.update_clampsForumVideoMinBetween0And20`
- `AgentForumPostRepository.countByCategoryAndSimDate` — PASS, `AgentForumPostRepository.java` `@Query` + `FUNCTION('DATE', p.createdAt)`
- `ForumGenerationService.ensureVideoQuota` — PASS, `ForumGenerationService.ensureVideoQuota` 在主流程末尾调用
- 主 LLM 返 0 → 兜底 LLM 返 1 video_series → 落库 — PASS, `ForumGenerationServiceVideoQuotaTest.noVideoToday_triggersFallbackLlm`
- 当天已有 → 跳过兜底 LLM — PASS, `hasVideoToday_skipsFallbackLlm`
- 兜底 LLM 拒答/异常 → warn 不阻塞 — PASS, `fallbackLlmFails_logsAndContinues`
- `forumVideoMinPerDay=0` → 不兜底 — PASS, `videoMinDayZero_disablesFallback`
- 兜底帖子走 `postRepo.save` + `mediaService.generateAttachments` — PASS, `ensureVideoQuota` 内同主流程路径
- 每次 action 检查(非 tick 缓存) — PASS, 调用点为 `generateFromAction` 末尾(每 action 一次)
- `WorldController.SettingsReq.forumVideoMinPerDay` + 透传 — PASS, `WorldController.java:557` + `updateSettings` 调用点

---

## 正确性(correctness)

- **测试覆盖**:3 fix 各自单测,4+1+4=9 个新测 + 2 个 WorldSettingsServiceTest 增强 + 1 个 ForumGenerationServiceTest 调整,全绿。
- **回归**:`mvn test` 整体 354 passed / 0 fail / 2 skip(0 新增 fail)。
- **TDD 守则**:3 个 fix 全部 RED → GREEN 走完,没有先写实现再补测。
- **T3.1 边界**:video_series content 必须 ≥50 字符(parseResponse 长度校验)— 测中 53 字符(中文),踩中后调长,见 commit diff。

---

## 一致性(coherence)

- **命名**:`forumVideoMinPerDay` / `countByCategoryAndSimDate` / `ensureVideoQuota` 全部对齐 spec 03 命名。
- **错误处理**:`ensureVideoQuota` 整体 `try/catch`,内部每条 warn,符合 spec "不影响主流程"约束。
- **daily cap 双重保护**:`ensureVideoQuota` 既查 `postRepo.countByCategoryAndSimDate`(全镇),又查 `countPostsToday`(单 agent cap),任一被命中都跳过。
- **DB schema**:init.sql CREATE + ALTER 双管齐下,migration.sql 单独存档,符合既有 convention。
- **Lombok 集成**:WorldSettingsService.update 新增 1 参,所有调用方(controller / test)同步更新,无遗漏。

---

## 副作用(unintended scope)

| 文件 | 改动类型 | 评估 |
|---|---|---|
| `infra/mysql/init.sql` | 加列(2 处:CREATE + ALTER) | 必要,符合既有 convention |
| `notebooklm-app/changes/world-content-restraint-v2/migration.sql` | 新建 | 必要,migration 单独存档 |
| `WorldSettingsServiceTest` | 加 1 测 + 调方法签名 | 必要,改 update 签名后必须 |
| `ForumGenerationServiceTest` | 加 stub + 改 import | 必要,ensureVideoQuota 会让原 1 次 LLM 变 2 次 |

无意外扩大。

---

## Risk(待用户确认 / 部署后验证)

1. **F1 智谱 API 真机验证** — 沙盒被 auto classifier 拦截,无法 curl 直连。需要用户部署后手动 curl 智谱 `/images/generations` 用 `watermark_enabled:false` + `watermark:false`,下载图,人工确认右下无 "AI生成" 角标。
2. **F2 `mediaEnabled()` 异常兜底** — 用 `try/catch` 返 false,意味着 settingsService 故障时全部 produce 静默跳过(0 产出)。保守安全,与 spec 守卫语义一致。
3. **F3 token 成本** — 主流程 + 兜底最多 2 次 LLM/action。若全镇 16 agent + 30s tick,理论 +50% token(只在缺 video 时)。2 个 LLM 都不会超 1k input token,可控。
4. **F3 主 LLM 误返 video_series** — 兜底逻辑 `videos = posts.stream().filter(video_series)` 已过滤,主流程自己发 video_series 不算兜底产生,正确。

---

## 结论

**Verdict**: **PASS**

3 fix 全部 spec-compliant + 测试全绿 + 354 回归无新增 fail。代码改动最小,无 scope 漂移。
Wave W1 完成,可推进部署 + DP-6 验证。

部署需要用户执行 docker compose build/up(沙盒不能代为执行)。
