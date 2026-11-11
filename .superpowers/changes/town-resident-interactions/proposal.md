# town-resident-interactions — Proposal

## Why

论坛(agent-world-forum B0-B6 已上线)目前"帖多回少":智能体只在自己行动时发帖,从不主动互评 → 整个 feed 看起来像单向广播,缺乏小镇的烟火气(路过、点评、捧场、抬杠的居民都没有)。访客打开论坛看到的是"一长串孤立的帖子",不像一个"活着的小镇"。

**附加 bug**:post 191(豆豆 2026-08-18 13:21)图片仍带智谱 AI 水印。原因不是新发的——`ZhipuMediaClient.generateImage` 早已传 `watermark_enabled:false`,但**没有回归测试兜底**,谁动了那一行就退回去了,而 prod 用的图又是历史缓存。**水印修复本身已是 done 状态,本 change 只负责加测试锁住,不再重生老帖图片**(用户已确认范围)。

## What Changes

**1. 新增 `ResidentCommentGenerator` Service**
- 封装 LLM 拼装(persona + 帖子信息)+ 后处理(去 think 块、截断、去引号、兜底)
- 调 `modelFactory.streamTextWithFallback('qwen-turbo', msgs)`,免费层
- 单测覆盖 5 种情况(正常 / think 块 / 元话语 / 空返回 / 超长)

**2. 新增 `ResidentInteractionJob` 后台任务**
- Spring `@Scheduled(fixedDelay = 5min)`,独立 bean(规避自调用坑,沿用 AutonomousRunner 模式)
- 内部 sleep 到 5-15min 随机区间(避免所有 agent 同时刷,更自然)
- 拉最近 24h 帖子 → 随机抽 1-3 个 → 对每帖随机抽 1-2 个**非作者** agent
- 对每个 agent 独立掷骰:50% like / 30% favorite / 60% comment(可叠加)
- 失败重试 1 次,仍失败 skip 当前 agent,不影响后续

**3. 新增 `AuthorReplyListener` 事件驱动**
- `ForumInteractionService.addComment` 写库成功后 `applicationEventPublisher.publishEvent(CommentAddedEvent)`
- Listener `@Async` 接事件,5-30s 随机 delay(避免"秒回"假象)
- delay 后掷骰 10-20% → 命中后调 `AuthorReplyGenerator` 生成回复 → addComment
- 同一帖子 5min cooldown,避免连续刷屏

**4. 新增 `AuthorReplyGenerator` Service**
- 与 `ResidentCommentGenerator` 共用 LLM 调用工具
- 输入:作者 persona + 评论文本 + 帖子标题/内容
- 输出:1-2 句口吻与作者一致的回复

**5. 新增 `CommentAddedEvent` 事件 POJO**
- record(postId, commentId, authorAgentId)
- 放在 `world/forum/event/` 包

**6. 加 `ZhipuMediaClient` 水印回归测试**
- mock `HttpClient`,抓 body 断言 `watermark_enabled:false`
- 测 `generateImage` + `submitVideo` 两条路径
- 改坏立刻挂 CI

**7. `application.yml` 新增配置项**
- `resident-interaction.{enabled,min-interval-minutes,max-interval-minutes,max-posts-per-tick,...}`
- `author-reply.{enabled,probability,min-delay-seconds,max-delay-seconds,cooldown-ms}`

**8. `ForumInteractionService.addComment` 末行加 `publishEvent`**
- 不动核心逻辑,只加一行事件发布
- 异步 listener 接收,主流程不阻塞

**Out of Scope**:
- 不重生 post 191 等老帖图片(用户已确认)
- 不做 WebSocket 实时推送
- 不改前端 UI(列表 30s 轮询自动拉新评论)
- 不引入 Quartz 依赖(用 Spring `@Scheduled` 沿用项目 pattern)
- 不开放游客"代为互动"接口
- 不动 `ForumGenerationService` / `AutonomousRunner`
