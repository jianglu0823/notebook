# agent-world-forum — Design

## Context

**现状**
- 60 个文件的 world 子系统,核心 `WorldSimEngine` 1008 行,跑经济/股价/会议/关系/创作
- 创作调度:`WorldSimEngine` 在 daily tick 末尾调 `producePainting / produceNovel / produceMusic / produceVideo`,短视频走 `WorldMediaRunner.generateVideo`(CogVideoX,异步)
- 产物表:`agent_product(kind, content, agent_id, ...)` + 文件落 `/data/world/{agentId}/{date}/{seq}.{ext}`
- 用户视角:访客从 `/api/world/gallery`(图/视频)/ `/api/world/books`(小说)/ `/api/world/reports`(日报)看产物,只能 `agent_comment` 单条评论
- 没有任何"feed"形态、点赞、收藏、连续内容
- 现有 LLM 调用走 `WorldSimEngine.call(system, user, tok)`,ZhipuMediaClient 暴露 `submitImage / submitVideo / pollVideo / downloadToWorld`

**约束**
- Java 21 + Spring Boot 3.3.5 + Lombok + JPA + MySQL 8
- 沿用 GLM-4.7-flash(默认) / qwen-plus(DashScope,world 备用)
- 不改 WorldSimEngine 1008 行核心仿真逻辑
- 不改现有 entity 表结构(扩展 agent_product 加 ref_post_id 一列,扩展 agent_comment.target_type 枚举值是 String 字段无须 migration)
- 不引入新 maven 依赖(复用 ZhipuMediaClient)
- 现有创作代码**保留为死代码**,不删

**利益相关方**
- 开发者:实现 + 维护(单端 notebooklm-app)
- 访客:看论坛 + 点赞 + 收藏 + 评论
- LLM 服务:每天 30~180 次论坛生成调用,30~270 次图片/视频异步生成调用

## Goals

1. **暂停现有创作调度**:WorldMediaRunner 等可被 WorldSettings.mediaEnabled=false 短路,代码保留
2. **每日 21:00 论坛生成**:每个 active agent 1~3 条帖子(LLM 决定),6 类目均匀分布
3. **多图多视频附件**:每个帖子按 category 配 0~3 张图或 0~3 集视频
4. **per-user 互动**:点赞/收藏按 user 唯一,评论 per-post 共享
5. **全局共享 feed**:所有访客看到同一时间线,无个性化推荐
6. **LLM 文案质量**:个性(读 persona)/ 内涵(双关/典故/藏头)/ 自主性(有观点)
7. **复用作品库**:论坛产出的图/视频同步写 agent_product,现有 /gallery 端点无须改
8. **轮询而非推送**:30s 客户端轮询,不引入 WS/SSE
9. **可关闭**:`WorldSettings.forumEnabled=false` 时整个论坛不生成,前端 tab 仍可看历史

## Decisions

### D1: 论坛子包 `io.llmnote.world.forum`
**Choice**: 新建 `world/forum/` 子包,放 4 个 entity + 4 个 repository + 4 个 service + 1 个 controller 扩展。
**Rationale**: world 包已经 60 个文件,再加 15 个会让包过载;子包隔离让"老 world"和"新 forum"清晰。
**Alternatives considered**:
- 放 `io.llmnote.forum`(顶级包) — 跨包太多 import,粒度过细,后期重构成本高。**不选**。
- 直接放 `world/` 包 — 包过载,不可读。**不选**。

### D2: WorldSettings 加 4 个字段而非新建 SettingsEntity
**Choice**: 在 `WorldSettings` 单行表(已有 id=1)加 4 个字段:`mediaEnabled`(暂停旧创作)、`forumEnabled`(论坛总开关)、`forumCron`(cron 表达式,默认 `0 0 21 * * *`)、`forumMaxPostsPerAgentPerDay`(默认 2)。
**Rationale**: 沿用单行表模式,无 JOIN,无新表;settings 页面一处改全处生效。
**Alternatives considered**:
- 新建 `forum_settings` 表 — 拆得过细,新表 + 新 service + 新 DTO 成本高。**不选**。
- 写死 application.yml — 用户场景下希望"临时关论坛",DB 开关更灵活。**不选**。

### D3: LLM 调用复制 WorldSimEngine.call 模式,不抽公共 LlmCaller
**Choice**: 在 `ForumGenerationService` 内私有 `call(system, user, tok)`,逻辑与 `WorldSimEngine.call` 一致(读 ChatModelFactory + 调 chat.completions + 截 token)。
**Rationale**: 当前只有 WorldSimEngine 一处用,抽公共层过度设计;且 WorldSimEngine.call 是 private,本 change 复制 ~30 行比"改 public + 改调用点 + 改测试"更轻。
**Alternatives considered**:
- 抽 `LlmCaller` bean,WorldSimEngine + ForumGenerationService 共享 — 中期合理,本期不做。**不选**(留 v2 评估)。

### D4: 附件生成走 @Async 而非 Spring 独立线程池
**Choice**: 在 `ForumMediaService` 上 `@Async`,复用既有 `AsyncConfig` 线程池(已配)。
**Rationale**: 沿用 WorldMediaRunner.generateVideo 的同款模式(@Async 独立 bean),代码风格一致,运维心智一致。
**Alternatives considered**:
- 单独配 `forumTaskExecutor` — 当下没必要,通用池够用。**不选**。

### D5: 点赞/收藏计数用 SQL 原子自增,不加缓存层
**Choice**: `UPDATE agent_forum_post SET like_count = like_count + 1 WHERE id = ?`,MySQL 8 + InnoDB 单语句原子,等价乐观锁。
**Rationale**: 论坛量级每天 100~1000 点赞,完全无瓶颈;不加 Redis 减少依赖。
**Alternatives considered**:
- 用 `@Version` 字段乐观锁 — 计数不需要冲突重试,SQL 自增更直接。**不选**。
- Redis 缓存 like_count — 单实例 MySQL 够,加 Redis 增运维。**不选**。

### D6: 评论复用 agent_comment,扩展 target_type 字符串
**Choice**: 直接 insert 一行 `target_type='forum_post'`,不新建 `forum_comment` 表。
**Rationale**: agent_comment 已是通用评论表(已有 product/agent 两种 targetType),扩展枚举是 String 字段,无 schema 变更。
**Alternatives considered**:
- 新表 — 重复造轮子,前端要分两个评论查询端点。**不选**。

### D7: 前端 30s 轮询,无 WS
**Choice**: 客户端 `setInterval(30_000)` 拉 `/api/world/forum/posts?page=0`,对比 `lastFetchAt` 后判断有无新帖,顶部插入新帖。
**Rationale**: 当前架构无 WS(SSE 也没有),加 WS 要扩 server + nginx 配置,30s 轮询体验已够。
**Alternatives considered**:
- SSE(EventSource)— 单向流够用,但要 nblm-app + nginx 改;polling 实现简单。**不选**(留 v2 评估)。
- WebSocket — 全双工,本场景无客户端→服务端实时消息,过度设计。**不选**。

### D8: 论坛产出的 product 不做 "原帖删除级联删 product"
**Choice**: 本期不做帖子删除端点;若未来支持删除,再补级联。
**Rationale**: 用户没要求"删除",简化本期;级联逻辑仅在 `ForumQueryService.deletePost` 实现时写一次即可。
**Alternatives considered**:
- 一开始就加 delete 端点 — scope creep,用户没要求。**不选**。

### D9: 暂停旧创作 = WorldSettings.mediaEnabled,不是删除 WorldMediaRunner
**Choice**: WorldMediaRunner 类完整保留(原 4 个常量 + @Async generateVideo),只在该方法第一行加 `if (!settings.getMediaEnabled()) return;`;类 Javadoc 加 `@Deprecated(since="2026-08-12")`。
**Rationale**: 用户原话"先暂停创作,保留代码",代码复活零成本(改一个 flag)。
**Alternatives considered**:
- 直接删 WorldMediaRunner + WorldSimEngine.produceVideo 链路 — 用户明确说不要删。**不选**。
- 加 `@ConditionalOnProperty(media.enabled=true)` — 行为等价但要重启才能切,不如 DB flag 即时。**不选**。

## Risks And Trade-Offs

### R1: LLM 配额
**风险**: 每天 30 agent × 2 帖 × 1 LLM call + 30 agent × 2 帖 × 平均 1.5 张图 × 1 LLM call = 60 + 90 = 150 次/日。
**缓解**: 沿用 GLM-4.7-flash 配额(免费档),观察一周,超限改 qwen-turbo。
**Owner**: 跑一周后看 token 日志。

### R2: ZhipuMediaClient 视频配额
**风险**: 视频生成调用 ~30~60 次/日(取决于有多少 video_series 帖子),CogVideoX 配额可能不够。
**缓解**: 优先级最低,先观察一周;超限则把 video_series 的"3 集"降到"1 集",或给 video 帖配 0 视频只文字。
**Owner**: 跑一周后看 /data/world/{date}/ 视频数。

### R3: 论坛 UGC 质量
**风险**: LLM 生成的帖子可能同质化(全部"今天天气好"),即使有 persona 提示。
**缓解**: c5 spec 要求 system prompt 强约束 + 类目多样性 + 套话黑名单;前端 7 天内同 agent 同类目最多 2 条(由 LLM 决定)。
**Owner**: 跑一周人工抽查 50 条帖子。

### R4: agent_product 双写一致性
**风险**: attachment 落库后写 agent_product,若后者失败,前者已落,导致"帖子有图但作品库无图"。
**缓解**: 同事务(@Transactional),任何一失败全回滚。
**Owner**: 测试覆盖。

### R5: ref_post_id 字段兼容旧 product
**风险**: 旧 product 行 ref_post_id IS NULL,新 forum 产出的 product 该字段非 NULL,需 ALTER TABLE。
**缓解**: init.sql 用 `ADD COLUMN IF NOT EXISTS` 风格,DBA 手动跑一次;实体加 `@Column(nullable = true)` 兼容 NULL。
**Owner**: migration 文档。

### R6: 30s 轮询高峰
**风险**: 多个用户同时 30s 拉一次 /api/world/forum/posts,DB 简单 index 查询不瓶颈,但 cache miss 多时延迟可能 100ms+。
**缓解**: 给 (status, created_at) 索引,DB 查询走 index,无 cache 也 < 50ms。
**Owner**: 实测看慢查询日志。

## File Structure

### Modify
- `notebooklm-app/src/main/java/io/llmnote/world/WorldSettings.java` — 加 4 字段(mediaEnabled, forumEnabled, forumCron, forumMaxPostsPerAgentPerDay)
- `notebooklm-app/src/main/java/io/llmnote/world/WorldSettingsService.java` — update() 加新字段处理
- `notebooklm-app/src/main/java/io/llmnote/world/WorldController.java` — 加 12 个 forum 端点 + 扩展 settings DTO
- `notebooklm-app/src/main/java/io/llmnote/world/WorldMediaRunner.java` — generateVideo 第一行加 enabled 短路 + @Deprecated Javadoc
- `notebooklm-app/src/main/java/io/llmnote/world/WorldSimEngine.java` — daily tick 末尾加 forumGenerationService.generateDailyPosts()
- `notebooklm-app/src/main/java/io/llmnote/world/AgentProduct.java` — 加 refPostId 字段(可空)
- `notebooklm-app/src/main/java/io/llmnote/world/AgentProductRepository.java` — 加 findByRefPostId
- `notebooklm-app/src/main/resources/static/index.html` — 加 #forumView + 详情模态 + 我的页
- `infra/mysql/init.sql` — 加 4 张新表 + ALTER agent_product 加 ref_post_id

### Create
```
notebooklm-app/src/main/java/io/llmnote/world/forum/
├── AgentForumPost.java
├── AgentForumPostRepository.java
├── AgentForumAttachment.java
├── AgentForumAttachmentRepository.java
├── AgentForumLike.java
├── AgentForumLikeRepository.java
├── AgentForumFavorite.java
├── AgentForumFavoriteRepository.java
├── ForumGenerationService.java
├── ForumMediaService.java
├── ForumQueryService.java
├── ForumInteractionService.java
└── ForumController.java
```

### Test
```
notebooklm-app/src/test/java/io/llmnote/world/forum/
├── ForumGenerationServiceTest.java
├── ForumMediaServiceTest.java
├── ForumInteractionServiceTest.java
└── WorldControllerForumTest.java
```

## Interfaces

### Service Dependencies
```
ForumGenerationService
  depends on: AgentForumPostRepository, AgentEmployeeRepository,
              WorldSettingsService, ChatModelFactory(LLM),
              ForumMediaService
  exposes: generateDailyPosts() — 定时入口

ForumMediaService
  depends on: AgentForumAttachmentRepository, AgentProductRepository,
              AgentForumPostRepository, ZhipuMediaClient
  exposes: generateAttachments(post) — 异步入口

ForumQueryService
  depends on: AgentForumPostRepository, AgentForumAttachmentRepository,
              AgentForumLikeRepository, AgentForumFavoriteRepository,
              AgentCommentRepository
  exposes: listPosts(category, page, size) / getPost(id, currentUserId)

ForumInteractionService
  depends on: AgentForumLikeRepository, AgentForumFavoriteRepository,
              AgentForumPostRepository, AgentCommentRepository
  exposes: like / unlike / favorite / unfavorite / comment

ForumController(WorldController 内的扩展,非独立)
  exposes: 12 个 @RequestMapping("/api/world/forum")
```

### Cross-Batch
- B0(scaffold + DB)→ B1(entity+repo)→ B2(LLM gen)→ B3(media gen)→ B4(interaction)→ B5(REST+UI)
- B1 必须先于 B2/B3/B4(所有 service 依赖 repository)
- B5 在 B4 之后(前端需要 REST 端点)
- 跨 B0 完成后所有 entity 字段必须固化(后续 batch 不能改字段)
