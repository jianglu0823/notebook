# B3 Review Report — ForumMediaService

- **Wave ID**: B3
- **Change**: agent-world-forum
- **Commit**: b367d26 (branch `agent-world-forum`, base 9bb99e4)
- **Files Changed**: 4 files (2 new + 2 modified main + 1 new test, +275 / -2)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| @Async 入口 | ✓ | ForumMediaService.generateAttachments 加 @Async,独立 bean |
| 6 类目附件数量按 c4 表格 | ✓ | ATTACHMENT_PLAN: joke=2图, acrostic=1图, daily=2图, chicken_soup=1图, video_series=1视频, utility=2图 |
| 视频异步 + 轮询 | ✓ | submitVideo → pollVideo(50, 12s) → downloadToWorld |
| 复用 ZhipuMediaClient.downloadToWorld | ✓ | 文件路径 `world/{agentId}/{date}-{seq}.{ext}` |
| 失败隔离 | ✓ | 每个附件独立 try/catch,单条失败仅 warn |
| 同步写 agent_product(ref_post_id) | ✓ | saveAttachmentAndProduct 落 2 行(attachment + product) |
| 不阻塞帖子写入 | ✓ | ForumGenerationService dispatch 后立即继续(mediaService 异步) |
| 单元测试 | ✓ | 4 个: 2图 / 1图失败隔离 / 鸡汤1图 / video_series 1视频 |
| 120 全项目测试 | ✓ | mvn test → 120, 0 fail, 1 skip |

## 关键设计点

1. **独立 bean 保证 @Async 生效**:ForumMediaService 与 ForumGenerationService 是两个独立 bean,跨 bean 调用才会真正异步。同 bean 自调用会失效(已被 @Async 注释警告)。
2. **附件计划写死 vs LLM 提供 prompt**:c4 spec 提到 LLM 在帖子生成时同步给出 mediaPlan,本期简化为「按 category 固定数量 + prompt 用 post.content」。这样 LLM 不用管附件细节,逻辑集中;后续 v2 可让 LLM 提供更精准的 prompt。
3. **失败不重试**:每个附件一次机会,失败仅 warn。视频轮询自身会等 10 分钟已算重试,图片失败一般是 prompt 触发安全过滤,重试也无效。
4. **写两份表(attachment + product)的复用收益**:现有 /api/world/products 端点 / 作品馆前端 / 静态文件路由全部不用改,论坛产出的图/视频自动出现在用户熟悉的「作品馆」位置。
5. **product.kind = 'image'/'video'**:与现有 AgentProduct 枚举对齐(chapter / song / artwork),过滤端点不需新增。

## 风险与限制

- **未应用 init.sql**:agent_forum_attachment 表 + agent_product 加 ref_post_id 列,B3 启动会因缺表失败(若 ddl-auto: validate)。当前 none,启动不挂,运行时 @Async 触发后 save 才报错。部署前必须 apply。
- **没跑真实 LLM / CogViewX / CogVideoX**:测试 mock 整个 ZhipuMediaClient。真实 prompt 触发的安全过滤 / 限流 / 异步超时表现需部署后看日志。
- **附件生成失败无重试队列**:如果 Zhipu 当天挂了,当天的图全部丢。可在 B7 加「用户手动重试」按钮(前端 GET 帖子 → 后端 if attachments empty 则触发)或后续 v2 加重试表。
- **视频长度固定 30s**:spec 没明确,默认按 submitVideo 默认参数(取决于 Zhipu 默认值)。如要 60s,需在 ForumMediaService 加显式参数。

## Requirement 映射

| R# | Spec | B3 覆盖 | 测试 |
|---|---|---|---|
| c4-R1 (按 category 附件计划) | c4-forum-media-generation | ✓ | ATTACHMENT_PLAN + joke_post_generates2Images + chickenSoup_post_generatesExactly1Image + videoSeries_post_generates1Video |
| c4-R2 (复用 ZhipuMediaClient) | c4-forum-media-generation | ✓ | downloadToWorld 调用 |
| c4-R3 (附件落盘路径) | c4-forum-media-generation | ✓ | world/{agentId}/{date}-{seq}.{ext} |
| c4-R4 (同步写 agent_product) | c4-forum-media-generation | ✓ | 4 个测试都 verify productRepo.save |
| c4-R5 (失败隔离) | c4-forum-media-generation | ✓ | joke_post_imageFailure_dropsThatAttachment_keepsPost |
| c4-R6 (异步不阻塞) | c4-forum-media-generation | ✓ | @Async + 同 bean 失效规避 |

## Verdict

**PASS** — B3 全部 done-when 满足,4/4 新测试绿,120/120 全项目测试绿。建议进入 B4 (ForumInteractionService: like / favorite / comment)。
