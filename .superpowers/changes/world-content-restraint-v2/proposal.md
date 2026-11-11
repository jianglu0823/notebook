# Proposal — world-content-restraint-v2

## Why

三个产线 regression 在同一天被用户发现,根因是 town-resident-interactions
切到"论坛唯一内容源"模式时**没有做完整的代码层拦截** + 一个水印 bug
没真正修好。这次集中修。

### Bug 1:Zhipu 论坛图片水印还在

用户最新报告:论坛帖子生成的图(智谱 cogview-3-flash)右下角仍有"AI生成"
水印。town-resident-interactions W3 给 `ZhipuMediaClient.generateImage` /
`submitVideo` 加了 `body.put("watermark_enabled", false)`(line 57 / 90),
代码看起来正确。但实际部署容器生成的新图**仍带水印**。根因待查:

**假设 A**:Zhipu 免费层(cogview-3-flash)不真正支持 `watermark_enabled: false`
→ API 收到参数但不生效,仍强制加水印

**假设 B**:部署容器中的 jar 是 W3 之前的旧版本,根本没拿到 fix

**假设 C**:`watermark_enabled` 字段名错了,正确名是 `no_watermark` 或 `watermark: false`
(对象 vs 顶层字段)

**修法**:本轮做"belt-and-suspenders"——在 `ZhipuMediaClient.generateImage` 里
既保 `watermark_enabled: false` 顶层字段,又加 `watermark: false` 对象,让两种
命名都能命中。部署前先 curl 一发 Zhipu API 确认哪个真生效,记录到测试用例。

### Bug 2:`media_enabled=false` 形同虚设

用户之前在 `agent_world_settings` 把 `media_enabled` 置 false,期望论坛接管后所有
老式创作(画作/短片/小说/歌词)全部停掉。但代码只在一处短路:

| 路径 | 是否检查 `media_enabled` | 当前行为 |
|------|------------------------|---------|
| `WorldMediaRunner.generateVideo` | ✅(B0 1fc2108) | 短路 return |
| `WorldSimEngine.produceArtwork` | ❌ | 调 `mediaClient.generateImage` 直产图 |
| `WorldSimEngine.produceVideo` | ❌ | 先落 `AgentProduct` 占位行再调 runner |
| `WorldSimEngine.produce`(novel/song)| ❌ | 直产文本 |
| `WorldSimEngine.maybeCreate` | ❌ | 调度入口完全无守门 |
| `WorldSimEngine` 类本身 | ❌ | 根本没注入 `WorldSettingsService` |

等于"代码里就没看过这个 flag"。用户配置永远无效,小镇每天还在生图/写小说。

### Bug 3:论坛没有视频

`video_series` 类目在 `ForumGenerationService` 里支持,`ForumMediaService` 也实现了
1 帖 1 视频生成。但 LLM 极少挑这个类目(只占 6 个里 1 个,默认倾向 daily/joke/
acrostic 三纯图),结果全镇每天 0 帖带视频,与"论坛是唯一内容源"的预期不符。

## What Changes

### F1:修 Zhipu cogview 水印(双保险)

`ZhipuMediaClient.generateImage` 在 body 里同时设置:
- `watermark_enabled: false`(顶层,既已存在)
- `watermark: false`(在嵌套对象里,作为冗余)

如果两个都没用,加 manual 后处理(用 ffmpeg 裁剪右下角水印区域,作为最后手段)。

部署前先 curl 测 Zhipu API,看哪个参数名真生效,把有效那个写进测试断言。

### F2:WorldSimEngine 守门 media_enabled

- `WorldSimEngine` 注入 `WorldSettingsService`(走 `@RequiredArgsConstructor` 自动连)
- `maybeCreate()` 顶部加 `if (!settingsService.get().getMediaEnabled()) return null;`
  这是**主守门**,覆盖 novel/song/image/video 四类产出
- `produceArtwork` / `produceVideo` / `produce` 三个方法加**防御性二次短路**
- `produceVideo` 即使守门,占位行也不再写,直接 return null

### F3:ForumGenerationService 兜底 video_series 配额

- `ForumGenerationService.generateFromAction` 在 LLM 返回 0 条时,
  查当天累计 `video_series` 计数
- 若当天 0 条 `video_series`,**额外再问一次 LLM**,prompt 强制要求
  "必须发 1 条 video_series",直到拿到为止(或最多 1 次重试防死循环)
- 配额上限:全镇每天**至少 1 条** `video_series`(用户选择 N=1)
- 配置:加 `WorldSettings.forumVideoMinPerDay`,默认 1

## Scope Fence

### In Scope

- 修改 4 文件:`ZhipuMediaClient.java` / `WorldSimEngine.java` /
  `ForumGenerationService.java` / `WorldSettings.java`(+ WorldSettingsService update)
- 加 1 ZhipuMediaClient 字段 + 加 ZhipuMediaClientTest 用例(验证双字段)
- 新增 3 测试:`ZhipuMediaClientWatermarkTest` / `WorldSimEngineMediaGateTest` /
  `ForumGenerationServiceVideoQuotaTest`
- 字段加 1 个:`WorldSettings.forumVideoMinPerDay`(默认 1)
- 部署前 curl 测 Zhipu API 验证
- 部署 + 验证(测均绿 + 产线抓 1 帖 video 截图)

### Out of Scope

- **不动 XhsService**——之前误以为是它,实际是 Zhipu 路径(已修正)
- **不动 Zhipu 的 video 路径**——`submitVideo` 也有 `watermark_enabled: false`,
  本轮只动 image 路径
- **不动 WorldMediaRunner**——它已正确短路
- **不改 forum 的其他类目**(joke/acrostic/daily/chicken_soup/utility)
- **不动 XhsController 与小红书其他端点**
- **不动前端**

## Risks

| 风险 | 缓解 |
|------|------|
| Zhipu 免费层彻底不支持去水印,API 忽略所有参数 | 走 ffmpeg 裁剪兜底(右下角 ~10% 区域) |
| 双字段 API 拒绝(400) | curl 先测,不破坏现状再部署 |
| `media_enabled=false` 后用户突然看到画作/小说消失 | 用户的取消创作指令就是期望此结果 |
| ForumGenerationService 重试 LLM 增加 token 成本 | 至多 1 次兜底,且仅在缺视频时触发,极低频 |

## Acceptance Criteria

1. `mvn test` 全绿(估计 198+ 个,3 个新测试)
2. 部署后 24h 内 forum 至少有 1 条带视频附件的帖子
3. 部署后 7 天 `agent_product` 表的 `kind IN (image, video, chapter, song)` 行为
   严格为 0(假设 `media_enabled=false` 持续)
4. 部署后**新生成**的论坛图无"AI生成"水印(由 ZhipuMediaClient 修复 + 截图验证)
5. 论坛手动发 `video_series` 类目帖子仍能正确生成视频(回归测试)
