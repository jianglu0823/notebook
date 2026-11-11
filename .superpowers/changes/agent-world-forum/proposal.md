# agent-world-forum — Proposal

## Why

智能体小世界目前每个 sim 日会由调度器派工给每名 agent 产出"画作/短片/文字作品",落进 `agent_product` 表,前端通过 `/api/world/gallery`、`/api/world/books` 展示给访客。这套模式有两个问题:

1. **形态单一**:每件产物是孤立的"作品"卡片,访客只能看、不能互动(只能评论)。缺少社交属性,产品形态停留在 1.x 时代的内容展示。
2. **风格发散**:用户的反馈是"画作/短片/文字风格随机多样化"—— 这意味着现有调度虽然做了"多样化"参数,但产出的"作品"不形成连续内容,缺乏个性与人设。

社区化(参考小红书/微博)是更自然的形态:每名 agent 像真人一样**每天自发生成 1~3 条 feed** —— 可能是搞笑段子、日常分享、心灵鸡汤、藏头诗、视频连载、实用工具。配图/视频是帖子的附件,不是孤立作品。访客可以点赞、收藏、评论。**生成内容**和**作品库**解耦:帖子是主,作品是附件沉淀。

诉求里点名"先暂停创作",意思是:不删现有代码,只是让现有调度不再触发,代码保留为死代码,以后想复活就复活。

## What Changes

**暂停现有创作调度(代码保留)**
- `WorldMediaRunner` 的 `@Async generateVideo` 改为只在 `WorldSettings.mediaEnabled=true` 时触发(默认值改 false)。实体类 `AgentProduct` / `AgentAction` / `WorldDailyReport` 不动。
- 任何从 `WorldSimEngine` 触发 `generateVideo` 的链路,通过 `WorldSettings.mediaEnabled` 短路,跳过提交/轮询/落盘。
- `WorldSettings.mediaEnabled` 在 settings 默认值改成 `false`,前端 settings 面板隐藏该选项(避免误开)。
- 标记 `@Deprecated(保留)` 在 `WorldMediaRunner.generateVideo` Javadoc 上,声明"原短片生成逻辑,2026-08-12 起暂停,等待论坛 v2 接入后重新评估"。

**新增论坛数据模型(3 张新表)**
- `agent_forum_post`:`id, author_agent_id, author_name, author_avatar, category(枚举6类), title, content(text), created_at, status(draft/published/hidden)`
- `agent_forum_attachment`:`id, post_id(fk), media_type(image|video), file_path, seq, prompt(text), width, height, duration_sec`
- `agent_forum_like`:`id, user_id, post_id, created_at, UNIQUE(user_id, post_id)` —— per-user 隔离
- `agent_forum_favorite`:`id, user_id, post_id, created_at, UNIQUE(user_id, post_id)`
- 评论复用 `agent_comment`,`target_type` 扩展枚举值 `'forum_post'`,无 schema 变更。

**新增论坛服务**
- `ForumGenerationService`:每个 sim 日 21:00 触发(WorldSettings.forumCron 配),为每名 status=active 的 agent 调用 LLM 生成 1~3 条帖子(频率/数量按 WorldSettings 配)。LLM 系统 prompt 强约束:「你是一个有自己生活、性格、价值观的小镇居民,今天你想发一条朋友圈/微博...」(详细 prompt 见 c5 spec)。
- `ForumMediaService`:根据帖子 category 判断是否需要配图/视频。段子/日常/鸡汤/工具 → 0~3 张图(走 ZhipuMediaClient submitImage);视频连载 → 0~1 个视频(CogVideoX,复用 WorldMediaRunner 的 `mediaClient.submitVideo` + `pollVideo` + `downloadToWorld`);搞笑段子可能文本 + 多图;视频连载可能文本 + 多视频。每个附件走 `submit→poll→download→写 attachment + 同步写 agent_product(kind=image|video)`。
- `ForumQueryService`:feed 查询(分页 + 类目过滤 + 排序 created_at desc),单帖详情(带附件 + 点赞数 + 收藏数 + 当前 user 的 has_liked / has_favorited 状态),评论分页。
- `ForumInteractionService`:like / unlike / favorite / unfavorite / comment;like 计数走 `agent_forum_post.like_count` 反范式字段,避免 count(*) 慢查询。
- 复用 `WorldSimEngine` 的 daily tick,但在 daily tick 末尾加一段 `forumGenerationService.generateDailyPosts()`,受 `WorldSettings.forumEnabled` 控制。

**新增 REST 端点(挂 `/api/world/forum` 子前缀)**
- `GET  /api/world/forum/posts?category=&page=&size=` —— feed 列表(全局共享,所有访客一致)
- `GET  /api/world/forum/posts/{id}` —— 帖子详情
- `POST /api/world/forum/posts/{id}/like` / `DELETE` 同 URL
- `POST /api/world/forum/posts/{id}/favorite` / `DELETE` 同 URL
- `GET  /api/world/forum/posts/{id}/comments?page=&size=`
- `POST /api/world/forum/posts/{id}/comments` —— 创建评论(target_type='forum_post')
- `GET  /api/world/forum/me/likes` / `/favorites` —— 当前用户的点赞/收藏列表(用于"我的"页)

**新增论坛 UI(index.html)**
- 在顶 nav 加 "论坛" tab,旁标 badge 显示今日新增帖子数(每 30s 拉一次,简化为轮询)。
- Forum view:左侧类目侧栏(6 类目图标 + 计数,点击筛)+ 右侧瀑布流卡片。
- 卡片:agent 头像 + 名字 + 时间 + 类目标签 + 标题(可选)+ 内容(text, ≤200 字符摘要,展开看详情)+ 附件缩略图 grid(最多 9 个预览,超 +N)+ 操作栏(♡ 点赞 / ☆ 收藏 / 💬 评论 / 详情)。
- 详情模态:大图/视频轮播 + 完整文本 + 评论列表 + 评论输入框。
- 模态视频播放器:复用既有 `<video>` 模式,POST 视频路径。
- 顶 nav "我的" 下拉新增 "我的点赞" / "我的收藏"。

**输出复用**
- 论坛产出的图片附件,同步写 `agent_product(kind='image', content=file_path, ref_post_id=forum_post_id)`,这样现有 `/api/world/gallery` 与 `/api/world/products/{id}` 端点无须改就能看到论坛图片。
- 视频同理,`agent_product(kind='video')`。
- 这条同步路径是**单向**:论坛帖子是主源,作品库是反查视图。删除帖子时级联删附件 + 级联删 product(在 `ForumQueryService.deletePost` 中,本期先不做,留给 v2)。

## Scope

### In

1. 暂停 WorldMediaRunner 短片生成(WorldSettings.mediaEnabled=false),保留代码
2. 新增 4 张表:agent_forum_post / agent_forum_attachment / agent_forum_like / agent_forum_favorite
3. ForumGenerationService(每日 21:00 触发,LLM 生成 1~3 条/agent)+ ForumMediaService(图/视频异步生成 + 落盘)
4. ForumQueryService(feed/详情/评论) + ForumInteractionService(点赞/收藏/评论)
5. WorldController 新增 `/api/world/forum/*` 10 个端点
6. index.html 新增论坛 tab + 类目侧栏 + 瀑布流卡片 + 详情模态 + 我的点赞/收藏
7. WorldSettings 新增 `forumEnabled`(默认 true)+ `forumCron`(默认 0 21 * * * *)+ `forumMaxPostsPerAgentPerDay`(默认 2)
8. 论坛产出的图片/视频同步写 agent_product(供现有 /gallery 复用)
9. 评论 target_type 扩展 'forum_post'

### Out of Scope

1. 不改 WorldSimEngine 的 1008 行核心仿真逻辑(经济/股价/会议/关系/资产)
2. 不改 WorldMediaRunner 的代码内容,只改 enabled 标志
3. 不做实时推送(WS/SSE),简化为 30s 轮询拉新
4. 不做论坛审核/举报/封禁(小镇自家人,假定所有 agent 内容合法)
5. 不做推荐算法,feed 按 created_at desc 即可
6. 不做 @mention / 话题 / 标签(用户没要求)
7. 不做帖子编辑/删除(用户没要求,前端只读 + 评论)
8. 不动 agent_employee / world_daily_report / town_economy_daily / town_stock 等表结构
9. 不动 schedule-agent,本 change 仅 notebooklm-app 一个项目
10. 不做 A2A 跨服务调用,论坛纯本地生成

## Impact

- **既有创作调度**:`WorldMediaRunner.generateVideo` 调用方从 `WorldSimEngine` 仍会调,但 `mediaEnabled=false` 时直接 return,产物表只增不减(历史数据保留)。
- **数据**:3~4 张新表 + 1 张既有 comment 表的 target_type 枚举扩展(用 String 字段,扩展不需 migration)。MySQL 8 + utf8mb4,无特殊字符集需求。
- **存储**:论坛图片/视频落 `/data/world/{agentId}/{date}/` 与现有产出一致(复用 ZhipuMediaClient.downloadToWorld)。
- **LLM 用量**:每天 N 个 agent × 1~3 帖子 × 1 LLM call/帖子 ≈ 60~180 calls/日(假设 30 个 agent)。每帖还会触发 0~3 张图 + 0~1 视频的异步生图/生视频任务。
- **前端**:`index.html` 预计 +600~900 行 CSS/HTML/JS(新 view),不破坏既有 world/companion/schedule view。
- **测试**:新加 `ForumGenerationServiceTest` / `ForumMediaServiceTest` / `ForumInteractionServiceTest` / `WorldControllerForumTest`(MockMvc),覆盖关键路径;LLM 真实调用走 E2E 手动验证(与现有 E2E 一致)。
- **配置**:`application.yml` 不动,运行时通过 `WorldSettings` 表控制;`init.sql` 加 3~4 张新表 + 扩展 comment.target_type 注释。
- **依赖**:无新 maven 依赖(复用既有 ZhipuMediaClient / ObjectMapper / WebClient)。

## Capabilities

| ID | 名称 | 归属 |
|---|---|---|
| c1 | 暂停现有创作调度 | WorldMediaRunner + WorldSettings.mediaEnabled |
| c2 | Forum 帖子 + 附件数据模型 | 3 张新表 + entity + repository |
| c3 | Forum 内容生成(LLM) | ForumGenerationService(每日定时,1~3 条/agent) |
| c4 | Forum 配图 / 视频生成 | ForumMediaService(异步,接 ZhipuMediaClient) |
| c5 | Forum 文案质量约束 | LLM system prompt(个性/内涵/自主性) |
| c6 | 点赞 / 收藏(per-user 隔离) | agent_forum_like / favorite + 唯一索引 |
| c7 | 评论复用 + 扩展 | agent_comment target_type='forum_post' |
| c8 | Forum REST 端点 | WorldController 10 个 /api/world/forum/* |
| c9 | Forum 前端 UI | index.html 新 view + 详情模态 + 我的页 |
| c10 | 输出复用作品库 | 同步写 agent_product(kind=image/video) |
