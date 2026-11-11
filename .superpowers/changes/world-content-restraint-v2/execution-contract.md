# Execution Contract — world-content-restraint-v2

## Intent Lock

修复三个产线 regression:水印未真正去除 / `media_enabled` 形同虚设 / 论坛无视频。
每个 fix 自带测试,顺序串行 W1(单波 3 task)。

## Approved Behavior

### F1:XhsService 通义万相水印关闭
- `XhsService.synthImage` 给 `ImageSynthesisParam` 补 `.addWatermark(false)`
- 配图右下角无"AI生成"水印

### F2:WorldSimEngine 守门 media_enabled
- 注入 `WorldSettingsService`(Lombok `@RequiredArgsConstructor` 自动连线)
- `maybeCreate()` 顶部主守门
- `produceArtwork` / `produceVideo` / `produce` 三个方法**防御性**二次短路
- `produceVideo` 守门后不写占位 `AgentProduct` 行(直接 return null)

### F3:ForumGenerationService 兜底 video_series
- `WorldSettings` 加 `forumVideoMinPerDay` 字段,默认 1
- `WorldSettingsService.update` 多 1 个参数透传
- `AgentForumPostRepository` 加 `countByCategoryAndSimDate` 方法
- `generateFromAction` 末尾加 `ensureVideoQuota(...)` 兜底
- 兜底 LLM 失败仅 warn,不阻塞主流程

## Out of Scope

- 不动 `ZhipuMediaClient`(水印已正确)
- 不动 `WorldMediaRunner`(守门已正确)
- 不动 `ForumMediaService`(1 帖 1 视频逻辑已正确)
- 不动 `XhsController` 与小红书其他端点
- 不动前端任何代码
- 不动 dailySettlement 的其他 phase(Phase C 关系/Phase D 互评)
- 不改 agent-world-forum 已有 5 字段
- 不删除任何代码(`WorldMediaRunner` 保留 `@Deprecated`)

## Constraints

| 约束 | 来源 |
|------|------|
| 零新依赖 | design D6 |
| 编译干净 | mvn compile 必过 |
| 全量测试 0 fail | mvn test 必过 |
| 不破坏 town-resident-interactions 195 个测试 | regression |
| 部署后 24h 内 forum 至少 1 条 video_series 帖 | acceptance #2 |
| 部署后 7 天 `agent_product` 行为 0 | acceptance #3(假设 media_enabled=false 持续) |

## Execution Batches

**W1**(单波,3 task 顺序):
1. T1:XhsService 水印(1 file + 1 test)
2. T2:WorldSimEngine 守门(1 file + 1 test)
3. T3:ForumGenerationService 兜底(5 files + 1 test)
4. T4:全量测试 + 构建 + 部署 + 24h 验证 + 水印截图

**并发**:无并发(单波串行,3 个 fix 互不阻塞但顺序做)。

## Test Obligations

| 测试 | 范围 | 必含场景 |
|------|------|---------|
| `XhsServiceWatermarkTest` | synthImage 传参 | addWatermark(false) 被传 |
| `WorldSimEngineMediaGateTest` | media_enabled 守门 | 4 produce 方法 + dailySettlement |
| `ForumGenerationServiceVideoQuotaTest` | 兜底配额 | 4 场景:缺/已有/失败/0 配额 |

## Review Gates

每个 task 后内部 review,Wave W1 完成后:
- W1-review.md 报告 spec 覆盖 + 代码质量
- `ssf execution review --wave W1 ... --verdict pass`

## Rewind Conditions

| 条件 | 路由 |
|------|------|
| F1 实施后 XhsService 已有测试 fail | F1 内部 fix |
| F2 实施后 `WorldSimEngine` 已有测试 fail | F2 内部 fix |
| F2 实施后 `WorldMediaRunner` 行为变化 | F2 撤销,回到 bridging |
| F3 实施后 forum 帖子生成 fail | F3 内部 fix |
| 部署后 24h 无 video_series 帖 | 调查 LLM 兜底 / 加 F3.5 retry |
| 部署后水印仍存在 | 调查 ImageSynthesisParam SDK 用法 |

## Approval

DP-3 等待用户明确批准。
