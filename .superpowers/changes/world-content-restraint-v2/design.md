# Design — world-content-restraint-v2

## Architecture Overview

三个独立修复,各打各的代码层,互不耦合:

```
                ┌──────────────────────────────────────┐
                │  XhsService (小红书配图)              │
                │  ──  F1: addWatermark(false)         │
                └──────────────────────────────────────┘

                ┌──────────────────────────────────────┐
                │  WorldSimEngine (居民每日结算)        │
                │  ──  F2: 注入 WorldSettingsService    │
                │  ──  F2: maybeCreate() 守门           │
                │  ──  F2: 4 个 produce 方法二次短路    │
                └──────────────────────────────────────┘

                ┌──────────────────────────────────────┐
                │  ForumGenerationService              │
                │  ──  F3: 兜底 video_series 配额       │
                │  ──  F3: 复用主 ChatModelFactory      │
                │  ──  F3: postRepo countByCategory     │
                └──────────────────────────────────────┘
```

## Decisions

### D1:WorldSimEngine 守门位置(单点 vs 多点)
**选择**:`maybeCreate()` 顶部单点主守门 + 3 个 `produce*` 方法**防御性**二次短路。

**理由**:
- 单点:在 `maybeCreate` 顶部查 `mediaEnabled` 一处搞定,简单清晰
- 多点:即使未来有人新增 `produce*` 方法或绕开 `maybeCreate` 直接调(测试代码或
  未来重构),每个 produce 方法自己再查一次,守门不破
- 代价:每次 produce 调用多一次 `settingsService.get()`,但它走 in-memory 缓存,
  开销 < 1ms,可忽略

### D2:produceVideo 占位行不写
**选择**:`media_enabled=false` 时,`produceVideo` 直接 return null,**不写占位行**。

**理由**:
- 占位行 `content=""` 在前端会渲染为"生成失败"卡片,体验差
- `agent-world-forum` 已经有 `ForumMediaService` 接管论坛视频,小镇自创视频在
  论坛模式下本就是噪声
- DB 写减少,统计更干净

### D3:forumVideoMinPerDay 字段 vs 硬编码 1
**选择**:加 `WorldSettings.forumVideoMinPerDay` 字段,默认 1,走
`WorldSettingsService.update` 透传。

**理由**:
- 用户可能调 0(完全不要视频)或调更高(多个视频/天)
- 与现有 4 字段同路径(autonomousEnabled / forumEnabled / forumCron / forumMaxPostsPerAgentPerDay)
  配置一致性高
- 零额外依赖,只是 entity + DTO + service.update 多一个参数

### D4:video 兜底 LLM 重试上限 = 1
**选择**:每次 `generateFromAction` 至多触发 1 次兜底 LLM。

**理由**:
- 重试 1 次覆盖 99% 场景(LLM 通常能给出 video_series)
- 不死循环:即使 LLM 一直拒答,也只多 1 次 LLM 调用/tick
- 兜底 LLM 失败仅 warn,不重试

### D5:不去掉 `WorldMediaRunner` 类
**选择**:保留 `WorldMediaRunner`(B0 已加 `@Deprecated`),代码完整保留。

**理由**:
- 它是 B0 短路的正确实现,等用户切回"非论坛模式"时,改一个 flag 即可复活
- 风险最小:不删除任何代码,只是确保它在 mediaEnabled=false 时正确短路

### D6:不动 ZhipuMediaClient
**选择**:本轮不动 `ZhipuMediaClient`。

**理由**:
- 它的 `watermark_enabled: false` 已正确(W3 已加)
- 用户报告的"水印"是来自 XhsService 通义万相路径,不是智谱
- town-resident-interactions W3 回归测试 `ZhipuMediaClientTest` 已在 worktree,
  跟着 3caabf6 进了 main

## File-Level Plan

| 文件 | 修改 | 说明 |
|------|------|------|
| `XhsService.java` | line 753-759 加 `.addWatermark(false)` | F1:1 行 |
| `WorldSimEngine.java` | 注入 WorldSettingsService + maybeCreate 守门 + 3 produce 二次短路 | F2:~15 行 |
| `WorldSettings.java` | 加 `forumVideoMinPerDay` 字段,默认 1 | F3:5 行 |
| `WorldSettingsService.java` | update() 多 1 个参数 | F3:1 行 |
| `WorldController.java` | SettingsReq DTO 多 1 个字段 | F3:1 行 |
| `ForumGenerationService.java` | generateFromAction 末尾加兜底 | F3:~30 行 |
| `AgentForumPostRepository.java` | 加 `countByCategoryAndSimDate` | F3:3 行 |
| `XhsServiceWatermarkTest.java` | 新文件 | F1 测试 |
| `WorldSimEngineMediaGateTest.java` | 新文件 | F2 测试 |
| `ForumGenerationServiceVideoQuotaTest.java` | 新文件 | F3 测试 |

## Test Strategy

| 测试 | 范围 | 依赖 |
|------|------|------|
| `XhsServiceWatermarkTest` | mock `ImageSynthesis` SDK,断言 `addWatermark(false)` 被传 | Mockito |
| `WorldSimEngineMediaGateTest` | mock `WorldSettingsService` + `ZhipuMediaClient` + `WorldMediaRunner`,跑 4 个 produce 方法 | Mockito |
| `ForumGenerationServiceVideoQuotaTest` | mock LLM + postRepo,断言兜底调用次数 | Mockito |

总测试增量:3 个新文件,~12 个 test case。

## Dependency & Integration

**零新依赖**。所有修改都在现有依赖图内:
- `ImageSynthesis.addWatermark()`:DashScope SDK 已有方法
- `WorldSettingsService.get()`:B0 已有
- `postRepo.countByCategoryAndSimDate`:Spring Data JPA 自动实现

## Rollout Plan

1. 全量测试 195 → 198+ 全绿
2. `mvn -DskipTests package` 产出新 jar
3. `docker compose build app && docker compose up -d app`
4. 监控日志:确认 `WorldSettings.mediaEnabled=false` 时 dailySettlement 无新
   `agent_product` 行
5. 24h 后查 `agent_forum_post.category=video_series` 计数 ≥ 1
6. XhsService 配图截图,确认无水印

## Rollback Plan

如有问题:`docker compose down app && docker compose up -d app` 回到前一镜像。
代码改动相互独立,可逐个 revert:
1. F3 失败:仅回滚 `WorldSettings.forumVideoMinPerDay` 加字段相关 + ForumGenerationService
2. F2 失败:仅回滚 `WorldSimEngine` 注入 + 守门
3. F1 失败:仅回滚 `XhsService.synthImage` 那一行

无需数据库迁移——所有改动是 Java 代码 + DB 字段加 1 个(forumVideoMinPerDay,
可空,旧行无值自动走默认 1)。
