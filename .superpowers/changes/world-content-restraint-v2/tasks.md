# Tasks — world-content-restraint-v2

## Wave Plan

W1(单波,顺序):F1 → F2 → F3。每个 fix 自带测试,顺序串行,互不阻塞。

| Task | 文件 | 工作量 | 测试 |
|------|------|--------|------|
| T1.1 红:XhsServiceWatermarkTest 写失败 | test | 5min | mock ImageSynthesis 断言 addWatermark(false) |
| T1.2 绿:XhsService.synthImage 加 addWatermark(false) | XhsService.java | 2min | 测试通过 |
| T1.3 提交 F1 | — | 1min | — |
| T2.1 红:WorldSimEngineMediaGateTest 写失败 | test | 10min | mock settings,4 个 produce 方法各 1 测 |
| T2.2 绿:WorldSimEngine 注入 + maybeCreate 守门 | WorldSimEngine.java | 5min | 部分测试通过 |
| T2.3 绿:produceArtwork/Video/novel 二次短路 | WorldSimEngine.java | 8min | 全测试通过 |
| T2.4 提交 F2 | — | 1min | — |
| T3.1 红:ForumGenerationServiceVideoQuotaTest 写失败 | test | 8min | 4 个场景 mock LLM |
| T3.2 绿:WorldSettings.forumVideoMinPerDay 字段 + update 透传 | WorldSettings.java + Service + Controller | 5min | — |
| T3.3 绿:AgentForumPostRepository.countByCategoryAndSimDate | repository | 3min | — |
| T3.4 绿:ForumGenerationService 兜底实现 | ForumGenerationService.java | 15min | 全测试通过 |
| T3.5 提交 F3 | — | 1min | — |
| T4.1 全量测试 | mvn test | 5min | 198/198+ 全绿 |
| T4.2 构建 | mvn package | 3min | jar 产出 |
| T4.3 部署 | docker compose | 5min | 容器 up |
| T4.4 24h 视频配额验证 | mysql query | 24h wait | 至少 1 条 video_series 帖 |
| T4.5 XhsService 配图水印检查 | 浏览器 + 截图 | 10min | 无 AI 生成 角标 |

## Detailed Steps

### T1: XhsService 通义万相水印

**T1.1 写测试**(RED):
```java
// XhsServiceWatermarkTest.java
@Test
void synthImage_passesWatermarkFalse() {
    // mock ImageSynthesis, capture ImageSynthesisParam
    // assertEquals(false, captured.getWatermark())
}
```

**T1.2 改代码**(GREEN):
```java
// XhsService.java line 753
ImageSynthesisParam param = ImageSynthesisParam.builder()
    .apiKey(...)
    .model(...)
    .prompt(prompt)
    .n(1)
    .size(IMG_SIZE)
    .addWatermark(false)   // ← 加这行
    .build();
```

### T2: WorldSimEngine 守门

**T2.1 写测试**(RED):
```java
// WorldSimEngineMediaGateTest.java
@Test void maybeCreate_returnsNullWhenMediaDisabled() {...}
@Test void produceArtwork_skipsGenerateImageWhenDisabled() {...}
@Test void produceVideo_skipsPlaceholderWhenDisabled() {...}
@Test void produceNovelOrSong_skipsLlmWhenDisabled() {...}
@Test void dailySettlement_producesZeroWhenDisabled() {...}
```

**T2.2-2.3 改代码**(GREEN):
```java
// WorldSimEngine.java
private final WorldSettingsService settingsService;  // 加 final

private AgentProduct maybeCreate(...) {
    WorldSettings ws = settingsService.get();
    if (ws.getMediaEnabled() != null && !ws.getMediaEnabled()) {
        return null;  // 主守门
    }
    // ... 原有逻辑
}

private AgentProduct produceArtwork(...) {
    if (!mediaEnabled()) return null;  // 二次短路
    // ... 原有逻辑
}

private AgentProduct produceVideo(...) {
    if (!mediaEnabled()) return null;  // 二次短路,且不写占位行
    // ... 原有逻辑
}

private AgentProduct produce(...) {
    if (!mediaEnabled()) return null;  // 二次短路
    // ... 原有逻辑
}

private boolean mediaEnabled() {
    WorldSettings ws = settingsService.get();
    return ws.getMediaEnabled() != null && ws.getMediaEnabled();
}
```

### T3: ForumGenerationService 兜底

**T3.1 写测试**(RED):
```java
// ForumGenerationServiceVideoQuotaTest.java
@Test void noVideoToday_triggersFallbackLlm() {...}
@Test void hasVideoToday_skipsFallbackLlm() {...}
@Test void fallbackLlmFails_logsAndContinues() {...}
@Test void videoMinDayZero_disablesFallback() {...}
```

**T3.2 改 WorldSettings**:
```java
// WorldSettings.java
@Column(name = "forum_video_min_per_day", nullable = false)
private Integer forumVideoMinPerDay = 1;
```

**T3.3 改 repository**:
```java
// AgentForumPostRepository.java
long countByCategoryAndSimDate(String category, LocalDate simDate);
```

**T3.4 改 ForumGenerationService**:
```java
// 在 generateFromAction 末尾加:
ensureVideoQuota(actor, action);

private void ensureVideoQuota(AgentEmployee actor, AgentAction action) {
    try {
        int min = settingsService.get().getForumVideoMinPerDay();
        if (min == null || min <= 0) return;
        long today = postRepo.countByCategoryAndSimDate("video_series", LocalDate.now());
        if (today >= min) return;
        // 兜底:再问一次 LLM,强制 video_series
        String fallbackPrompt = "...必须发 1 条 video_series...";
        String raw = askFallback(actor, action, fallbackPrompt);
        // 解析 + 落库,失败仅 warn
    } catch (Exception e) {
        log.warn("[forum-quota] video fallback failed: {}", e.getMessage());
    }
}
```

## Completion Definition

每个 task 完成需要:
1. 测试已写 + 跑过(RED → GREEN)
2. 编译干净
3. 提交进 git(commit message 简洁,关联 spec 编号)
4. 在 `progress.md` 标记 complete

Wave 完成需要:
- 所有 task 完成
- 全量 `mvn test` 0 fail / 0 error
- 写 W1-review.md,记录 spec 覆盖 + 代码质量
- 提交 review receipt

## Risk Per Task

| Task | 风险 | 缓解 |
|------|------|------|
| T1.1-1.2 | ImageSynthesisParam.addWatermark 误用 Boolean 而非 boolean | 看 SDK 源码确认类型 |
| T2.1-2.3 | produceVideo 移除占位行后,WorldMediaRunner 已有任务回填会失败 | WorldMediaRunner 也是查 mediaEnabled,守门,无影响 |
| T3.1-3.4 | 兜底 LLM token 突增 | 1 次/tick 上限,且仅在缺视频时触发 |
| T4.3 部署 | 配置未传 forumVideoMinPerDay,DB 旧行为 | DEFAULT 1 走 entity 初始化,旧行自动填 1 |
