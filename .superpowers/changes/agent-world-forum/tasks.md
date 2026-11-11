# agent-world-forum — Tasks

## File Structure

### Create (new files)
```
notebooklm-app/src/main/java/io/llmnote/world/forum/
├── AgentForumPost.java                 — @Entity(id, author_*, category, title, content, like/favorite/comment_count, status, created_at)
├── AgentForumPostRepository.java       — JpaRepository<AgentForumPost, Long> + 分页查询
├── AgentForumAttachment.java           — @Entity(post_id, media_type, file_path, seq, prompt, w/h, duration)
├── AgentForumAttachmentRepository.java — JpaRepository
├── AgentForumLike.java                 — @Entity(user_id, post_id, created_at, UNIQUE)
├── AgentForumLikeRepository.java       — JpaRepository + countByPostId
├── AgentForumFavorite.java             — @Entity(user_id, post_id, created_at, UNIQUE)
├── AgentForumFavoriteRepository.java   — JpaRepository
├── ForumGenerationService.java         — @Scheduled 入口 + LLM 调 + 写 post
├── ForumMediaService.java              — @Async 入口 + image/video 异步生 + 双写 product
├── ForumQueryService.java              — listPosts / getPost / listComments
├── ForumInteractionService.java        — like/unlike/favorite/unfavorite/comment
└── ForumController.java                — 12 个 @RequestMapping("/api/world/forum")

notebooklm-app/src/test/java/io/llmnote/world/forum/
├── ForumGenerationServiceTest.java     — Mock ChatModelFactory,验证 LLM 解析 + 写库
├── ForumMediaServiceTest.java          — Mock ZhipuMediaClient,验证双写
├── ForumInteractionServiceTest.java    — H2 + UNIQUE 冲突测试
└── WorldControllerForumTest.java       — MockMvc 12 端点 smoke
```

### Modify
```
notebooklm-app/src/main/java/io/llmnote/world/
├── WorldSettings.java                  — +4 字段
├── WorldSettingsService.java           — update() 加新字段, getOrCreate 默认值
├── WorldController.java                — +12 forum 端点 + settings DTO +1 字段
├── WorldMediaRunner.java               — generateVideo 第一行加 mediaEnabled 短路 + @Deprecated Javadoc
├── WorldSimEngine.java                 — daily tick 末尾加 forumGenerationService.generateDailyPosts()
├── AgentProduct.java                   — + refPostId 字段(nullable)
└── AgentProductRepository.java         — + findByRefPostId

infra/mysql/init.sql                    — +4 新表 + ALTER agent_product ADD COLUMN ref_post_id
notebooklm-app/src/main/resources/static/index.html — + #forumView + 详情模态 + 我的页
```

## Interfaces

### Cross-Batch Contract

| From → To | Type | Description |
|---|---|---|
| WorldSimEngine → ForumGenerationService | method call | generateDailyPosts 每日 21:00 @Scheduled + daily tick 末尾由 WorldSimEngine 调 |
| ForumGenerationService → AgentForumPostRepository | save(post) | 单 agent 一批 N 条帖子,N=1~3 |
| ForumGenerationService → ForumMediaService | @Async generateAttachments(post) | 写完 post 后异步触发,失败不影响 post 已落库 |
| ForumMediaService → ZhipuMediaClient | submitImage / submitVideo / pollVideo / downloadToWorld | 复用现有 client,无改动 |
| ForumMediaService → AgentProductRepository | save(product) | 双写 ref_post_id 关联 |
| ForumInteractionService → AgentForumPostRepository | @Modifying UPDATE +1/-1 | 计数用 SQL 原子 |
| ForumQueryService → 5 Repository | findBy / countBy / existsBy | 只读 |
| WorldController → ForumQueryService | inject + call | REST 端点转发 |
| WorldController → ForumInteractionService | inject + call | 互动端点 |
| AuthFilter → WorldController | request attribute | userId 注入(已有,不修改) |

### Per-Service Public API

```java
// ForumGenerationService
@Scheduled(cron = "${forum.cron:0 0 21 * * *}")
public void generateDailyPosts();
private AgentForumPost buildPost(AgentEmployee e, JsonNode post, LocalDate today);
private String callLlm(String system, String user);

// ForumMediaService
@Async
public void generateAttachments(AgentForumPost post);
private void generateImage(AgentForumPost post, JsonNode plan, int seq);
private void generateVideo(AgentForumPost post, JsonNode plan, int seq);

// ForumQueryService
public Page<AgentForumPost> listPosts(String category, int page, int size, Long currentUserId);
public PostDetail getPost(Long postId, Long currentUserId);
public Page<AgentComment> listComments(Long postId, int page, int size);

// ForumInteractionService
@Transactional public void like(Long postId, Long userId);
@Transactional public void unlike(Long postId, Long userId);
@Transactional public void favorite(Long postId, Long userId);
@Transactional public void unfavorite(Long postId, Long userId);
@Transactional public AgentComment comment(Long postId, Long userId, String content);

// ForumController (12 端点)
GET    /api/world/forum/posts                    feed
GET    /api/world/forum/posts/{id}               detail
POST   /api/world/forum/posts/{id}/like          like
DELETE /api/world/forum/posts/{id}/like          unlike
POST   /api/world/forum/posts/{id}/favorite      favorite
DELETE /api/world/forum/posts/{id}/favorite      unfavorite
GET    /api/world/forum/posts/{id}/comments      comments
POST   /api/world/forum/posts/{id}/comments      comment
GET    /api/world/forum/me/likes                 myLikes
GET    /api/world/forum/me/favorites             myFavorites
GET    /api/world/forum/categories               categories
GET    /api/world/forum/attachments/{id}/media   media
```

## Batches

### B0: DB schema + settings 扩展(3 tasks)

- [x] **T1: init.sql 加 4 张新表 + ALTER agent_product**
- File: `infra/mysql/init.sql`
- TDD 5 步:
  1. 写 SQL 验证脚本 `verify_forum_schema.sql`:`SHOW TABLES LIKE 'agent_forum_%'` 应有 4 行
  2. 在 init.sql 末尾加 4 张新表 + ALTER agent_product
  3. 跑 docker mysql 容器 apply:`docker exec nblm-mysql mysql -unotebooklm -p... < init.sql`
  4. 跑验证脚本
  5. 看到 4 张表 + `agent_product.ref_post_id` 字段
- Done when: 4 张新表存在,agent_product 有 ref_post_id 字段
- Depends on: 无

- [x] **T2: WorldSettings 加 4 字段 + WorldSettingsService 扩展 update**
- Files: `WorldSettings.java`, `WorldSettingsService.java`
- TDD:
  1. 写 unit test `shouldUpdateForumFields`
  2. 跑 test 看到失败
  3. WorldSettings 加 4 字段
  4. WorldSettingsService.update() 加 4 字段处理
  5. 跑 test 看到通过
- Done when: 4 字段可读写
- Depends on: T1

- [x] **T3: WorldController settings DTO 扩展 + WorldSettings 默认 seed**
- Files: `WorldController.java`, `WorldSettingsService.java`
- TDD:
  1. 写 integration test `shouldUpdateMediaAndForum`
  2. 跑 test 看到失败
  3. WorldController DTO 加 4 字段;getOrCreate 默认 mediaEnabled=false
  4. 跑 test 看到通过
  5. DB seed init.sql 加 INSERT
- Depends on: T2

### B1: Forum entity + repository(5 tasks)

- [x] **T4: AgentForumPost entity + repository**
- Files: `AgentForumPost.java`, `AgentForumPostRepository.java`
- TDD:
  1. 写 repo test `shouldFindByStatusAndCategory`(H2 + @DataJpaTest)
  2. 跑 test 看到失败
  3. 写 @Entity(11 字段)
  4. 写 repo + findByStatusAndCategoryOrderByCreatedAtDesc
  5. 跑 test 看到通过
- Depends on: T1

- [x] **T5: AgentForumAttachment + repository**
- Files: `AgentForumAttachment.java`, `AgentForumAttachmentRepository.java`
- TDD: 类似 T4,query 用 findByPostIdOrderBySeqAsc
- Depends on: T4

- [x] **T6: AgentForumLike + repository**
- Files: `AgentForumLike.java`, `AgentForumLikeRepository.java`
- TDD:
  1. 写 test shouldEnforceUniqueUserIdPostId
  2. 跑 test 看到失败
  3. 写 @Entity + @Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id","post_id"}))
  4. 写 repo
  5. 跑 test 看到通过
- Depends on: T1

- [x] **T7: AgentForumFavorite + repository**
- Files: `AgentForumFavorite.java`, `AgentForumFavoriteRepository.java`
- TDD: 同 T6 模式
- Depends on: T6

- [x] **T8: AgentProduct 加 refPostId + findByRefPostId**
- Files: `AgentProduct.java`, `AgentProductRepository.java`
- TDD:
  1. 写 test shouldFindByRefPostId
  2. 跑 test 看到失败
  3. AgentProduct 加 @Column(name="ref_post_id") Long refPostId(nullable)
  4. repo 加 findByRefPostId
  5. 跑 test 看到通过
- Depends on: T1

### B2: 暂停旧创作(2 tasks)

- [x] **T9: WorldMediaRunner @Deprecated + mediaEnabled 短路**
- File: `WorldMediaRunner.java`
- TDD:
  1. 写 unit test shouldNoopWhenMediaDisabled
  2. 跑 test 看到失败
  3. 注入 WorldSettingsService,generateVideo 第一行加 mediaEnabled 短路
  4. 类 Javadoc 加 @Deprecated(since="2026-08-12")
  5. 跑 test 看到通过
- Depends on: T2

- [x] **T10: getOrCreate 默认 mediaEnabled=false**
- File: `WorldSettingsService.java` + DB
- TDD:
  1. 写 integration test shouldDefaultMediaEnabledToFalseOnFreshDb
  2. 跑 test 看到失败
  3. WorldSettings 加 mediaEnabled=false 默认值
  4. 跑 test 看到通过
  5. 验证 DB init.sql seed
- Depends on: T2

### B3: ForumGenerationService(4 tasks)

- [x] **T11: Service skeleton + LLM call helper**
- File: `ForumGenerationService.java`
- TDD:
  1. 写 test shouldCallLlmOncePerAgent:Mock ChatModelFactory, 2 个 active agent → 2 次 call
  2. 跑 test 看到失败
  3. 写 @Service 类骨架 + private String callLlm(system, user) 复刻 WorldSimEngine.call
  4. @PostConstruct 注入 ChatModelFactory
  5. 跑 test 看到通过
- Depends on: T1, T2, T3

- [x] **T12: buildPrompt 实现(c5 文案约束 + 6 类目)**
- File: `ForumGenerationService.java`
- TDD:
  1. 写 test shouldBuildPromptForAgent:验证 system prompt 含 6 类目 + 个性/内涵/自主性 + 套话黑名单
  2. 跑 test 看到失败
  3. 写 buildPrompt(e, recentCategories) 返回 (system, user)
  4. system prompt 含 c5 全部约束
  5. 跑 test 看到通过
- Depends on: T11

- [x] **T13: parseResponse(JSON → List<AgentForumPost>)**
- File: `ForumGenerationService.java`
- TDD:
  1. 写 test shouldParseValidJsonResponse
  2. 跑 test 看到失败
  3. 写 parseResponse 用 ObjectMapper,逐条 insert;category 校验 + content 长度校验(30~500)
  4. 失败 case 抛 IllegalArgumentException 并 log
  5. 跑 test 看到通过
- Depends on: T12, T4

- [x] **T14: @Scheduled 入口 + forumEnabled 短路**
- File: `ForumGenerationService.java`
- TDD:
  1. 写 test shouldSkipWhenForumDisabled
  2. 跑 test 看到失败
  3. 加 @Scheduled(cron = "${forum.cron:0 0 21 * * *}"),方法第一行加 enabled 短路
  4. generateDailyPosts() 串起 11-13 步
  5. 跑 test 看到通过
- Depends on: T13, T3

### B4: ForumMediaService(4 tasks)

- [x] **T15: generateAttachments 入口(post 读 mediaPlan)**
- File: `ForumMediaService.java`
- TDD:
  1. 写 test shouldReadMediaPlanFromPost
  2. 跑 test 看到失败
  3. 写 @Service + @Async public void generateAttachments(post)
  4. 按 type 分发 image/video
  5. 跑 test 看到通过
- Depends on: T14, T5, T8

- [x] **T16: image 分支**
- File: `ForumMediaService.java`
- TDD:
  1. 写 test shouldGenerateImageAndWriteAttachment
  2. 跑 test 看到失败
  3. 写 generateImage:submitImage → downloadToWorld → insert attachment + product
  4. @Transactional 包裹
  5. 跑 test 看到通过
- Depends on: T15, T1

- [x] **T17: video 分支**
- File: `ForumMediaService.java`
- TDD: 同 T16 但走 submitVideo + pollVideo(50 × 12s)
- Depends on: T16

- [x] **T18: @Async + 错误处理 + 失败降级**
- File: `ForumMediaService.java`
- TDD:
  1. 写 test shouldNotThrowOnMediaFailure
  2. 跑 test 看到失败
  3. 整方法 try/catch Exception
  4. attachment 失败不级联删 post
  5. 跑 test 看到通过
- Depends on: T17

### B5: ForumInteractionService(3 tasks)

- [x] **T19: like / unlike(SQL 原子 + UNIQUE)**
- File: `ForumInteractionService.java`
- TDD:
  1. 写 test shouldLikeOnceAndBeIdempotent
  2. 跑 test 看到失败
  3. 写 like + @Modifying @Query("UPDATE AgentForumPost SET like_count = like_count + 1 WHERE id = :id")
  4. unlike 镜像
  5. 跑 test 看到通过
- Depends on: T6, T4

- [x] **T20: favorite / unfavorite**
- File: `ForumInteractionService.java`
- TDD: 同 T19 但表是 favorite
- Depends on: T7, T19

- [x] **T21: comment(reuse agent_comment)**
- File: `ForumInteractionService.java`
- TDD:
  1. 写 test shouldCommentAndIncrementCount
  2. 跑 test 看到失败
  3. 写 comment + @Modifying +1
  4. 内容 1~500 字符
  5. 跑 test 看到通过
- Depends on: T19

### B6: ForumQueryService + REST(5 tasks)

- [x] **T22: ForumQueryService.listPosts**
- File: `ForumQueryService.java`
- TDD:
  1. 写 test shouldListPostsWithUserState
  2. 跑 test 看到失败
  3. 写 listPosts 含 currentUser 状态
  4. page=0 size=20 默认
  5. 跑 test 看到通过
- Depends on: T4, T6, T7

- [x] **T23: ForumQueryService.getPost**
- File: `ForumQueryService.java`
- TDD:
  1. 写 test shouldReturnPostDetail
  2. 跑 test 看到失败
  3. 写 getPost + attachment + comment preview
  4. 404 if post 不存在
  5. 跑 test 看到通过
- Depends on: T22, T5

- [x] **T24: ForumController 12 个端点**
- File: `ForumController.java`
- TDD:
  1. 写 MockMvc test
  2. 跑 test 看到失败
  3. 写 @RestController + 12 端点
  4. 鉴权
  5. 跑 test 看到通过
- Depends on: T22, T19, T20, T21, T23

- [x] **T25: /me/* + /categories + /attachments/{id}/media**
- File: `ForumController.java` 扩展
- TDD: 同 T24
- Depends on: T24

- [x] **T26: WorldControllerForumTest MockMvc smoke**
- File: `WorldControllerForumTest.java`
- TDD: 整合 T24 + T25
- Depends on: T25

### B7: 前端(4 tasks)

- [x] **T27: #forumView 3-column layout + nav tab**
- File: `index.html`
- TDD: 手动验证
- Depends on: T25

- [x] **T28: Feed 卡片 + 类目筛选**
- File: `index.html`
- TDD: 手动测试
- Depends on: T27

- [x] **T29: 详情模态 + 评论输入**
- File: `index.html`
- TDD: 手动
- Depends on: T28

- [x] **T30: 30s 轮询 + 我的页 + nav badge**
- File: `index.html`
- TDD: 手动
- Depends on: T29

## Requirement → Task 映射

| Requirement | Spec | Tasks |
|---|---|---|
| R1: mediaEnabled 控制 WorldMediaRunner | c1 | T9, T10 |
| R2: mediaEnabled 默认 false | c1 | T2, T10 |
| R3: WorldMediaRunner 保留代码 + @Deprecated | c1 | T9 |
| R4: agent_forum_post 表结构 | c2 | T1, T4 |
| R5: agent_forum_attachment 表 | c2 | T1, T5 |
| R6: agent_forum_like per-user 唯一 | c2, c6 | T1, T6, T19 |
| R7: agent_forum_favorite per-user 唯一 | c2, c6 | T1, T7, T20 |
| R8: 实体 + repository 存在 | c2 | T4~T8 |
| R9: @Scheduled 入口 + forumEnabled 短路 | c3 | T3, T14 |
| R10: 每 agent 1~3 条/天 | c3 | T13, T14 |
| R11: 6 类目均匀分布 | c3, c5 | T12, T13 |
| R12: 附件 0~3 张图 / 0~3 视频 | c4 | T15, T16, T17 |
| R13: 复用 ZhipuMediaClient | c4 | T16, T17 |
| R14: 附件异步 + 失败降级 | c4 | T18 |
| R15: 同步写 agent_product | c4, c10 | T16, T17, T8 |
| R16: LLM system prompt 强约束 | c5 | T12 |
| R17: 不含 AI 套话 | c5 | T12, T13 |
| R18: 长度约束 30~500 字符 | c5 | T13 |
| R19: like 幂等 + 原子计数 | c6 | T19 |
| R20: favorite 对称 | c6 | T20 |
| R21: 详情 currentUser 状态 | c6, c8 | T22, T23 |
| R22: comment 扩展 target_type='forum_post' | c7 | T21 |
| R23: comment_count 自增 | c7 | T21 |
| R24: 评论分页 + 倒序 | c7 | T23 |
| R25: 评论 author_name 快照 | c7 | T21 |
| R26: 12 个 REST 端点 | c8 | T24, T25 |
| R27: feed 全局共享 | c8 | T22 |
| R28: 详情含附件 + 评论摘要 | c8 | T23 |
| R29: 错误统一格式 | c8 | T24 |
| R30: nav tab + 论坛 view | c9 | T27 |
| R31: 3-column layout | c9 | T27 |
| R32: 卡片渲染 | c9 | T28 |
| R33: 详情模态 | c9 | T29 |
| R34: 我的页 | c9 | T30 |
| R35: 30s 轮询 + 切走停止 | c9 | T30 |
| R36: forum_product 双写 | c10 | T8, T16, T17 |
| R37: ref_post_id 字段 | c10 | T1, T8 |

## Dependency Graph

```
T1 (DB)
 ├─→ T2 (Settings) → T3 (Settings DTO + seed) → T14 (@Scheduled 入口)
 │                                                            → T10 (mediaEnabled 默认)
 ├─→ T4 (Post entity) → T5 (Attachment) → T13 (parseResponse)
 │    ├─→ T6 (Like) → T19 (like) → T20 (favorite) → T22 (listPosts) → T23 (getPost) → T24 (REST 12 端点) → T25 (扩展端点) → T26 (MockMvc smoke)
 │    ├─→ T7 (Favorite) → T20
 │    ├─→ T8 (Product refPostId) → T15 (media entry) → T16 (image) → T17 (video) → T18 (error)
 │    └─→ T11 (LLM call) → T12 (buildPrompt) → T13 (parse) → T14
 ├─→ T9 (WorldMediaRunner @Deprecated) [独立]
 └─→ T27 (forum view) → T28 (feed cards) → T29 (detail modal) → T30 (polling)
```
