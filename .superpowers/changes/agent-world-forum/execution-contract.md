# execution-contract.md — agent-world-forum

## Intent Lock

- **变更名称**: `agent-world-forum`
- **要解决的问题**: 智能体小世界当前产物(画作/短片/小说/文字)是孤立"作品"卡片,访客只能看+单条评论,缺少社交形态(feed/点赞/收藏/连续内容)。用户要求"先暂停创作,新增论坛",论坛产出的图/视频进入现有作品库。
- **范围内**:
  1. 暂停 WorldMediaRunner 短片生成(WorldSettings.mediaEnabled 短路),代码保留
  2. 新增 4 张表:agent_forum_post / attachment / like / favorite
  3. ALTER agent_product 加 ref_post_id(可空)
  4. WorldSettings 加 4 字段(mediaEnabled/forumEnabled/forumCron/forumMaxPostsPerAgentPerDay)
  5. ForumGenerationService(@Scheduled cron 21:00,LLM 生成 1~3 条/agent)
  6. ForumMediaService(@Async,接 ZhipuMediaClient,双写 agent_product)
  7. ForumInteractionService(like/favorite 幂等 + 原子计数;comment 复用 agent_comment)
  8. ForumQueryService + ForumController 12 个 REST 端点
  9. 前端 #forumView 3-column layout + 详情模态 + 我的页 + 30s 轮询
  10. agent_comment.target_type 扩展 'forum_post' 枚举值(String,无 schema)
- **范围外**:
  1. WorldSimEngine 1008 行核心仿真逻辑(经济/股价/会议/关系/资产)不动
  2. 不删 WorldMediaRunner 原代码(暂停 ≠ 删除)
  3. 不做实时推送(WS/SSE),30s 轮询
  4. 不做论坛审核/举报/封禁
  5. 不做推荐算法
  6. 不做 @mention / 话题 / 标签
  7. 不做帖子编辑/删除
  8. agent_employee / world_daily_report / town_economy_daily / town_stock 等表结构不动
  9. 不动 schedule-agent
  10. 不做 A2A 跨服务调用

## Approved Behavior

### 已批准需求摘要(37 个 Requirement,跨 10 spec)

| # | Spec | Requirement | 关键断言 |
|---|---|---|---|
| R1 | c1 | mediaEnabled MUST 控制 WorldMediaRunner | settings.mediaEnabled=false 时 generateVideo 第一行 return,不调 ZhipuMediaClient |
| R2 | c1 | mediaEnabled MUST 默认 false | WorldSettings.getOrCreate() 返回值 mediaEnabled=false |
| R3 | c1 | WorldMediaRunner 原代码 MUST 保留 | 4 个常量 + @Async generateVideo + 主体保留,加 @Deprecated(since="2026-08-12") |
| R4 | c2 | agent_forum_post MUST 有 12 字段 + 3 索引 | (status,created_at desc) / (author_agent_id,created_at desc) / (category,status,created_at desc) |
| R5 | c2 | agent_forum_attachment MUST 有 9 字段 + 索引 | (post_id,seq) |
| R6 | c2 | agent_forum_like / favorite MUST per-user 唯一 | UNIQUE(user_id, post_id) + (post_id) 索引 |
| R7 | c2 | 4 个 entity + 4 个 JpaRepository MUST 存在 | 包路径 io.llmnote.world.forum |
| R8 | c2 | AgentProduct MUST 加 refPostId 字段 | @Column(name="ref_post_id") Long nullable |
| R9 | c3 | @Scheduled cron MUST 0 0 21 * * * | forumEnabled=false 时第一行 return |
| R10 | c3 | 每 agent MUST 生成 1~3 条 | LLM 决定,WorldSettings.forumMaxPostsPerAgentPerDay 上限 |
| R11 | c3 | 6 类目 MUST 均匀分布 | system prompt 含 7 天 category 分布 |
| R12 | c4 | 附件 MUST 0~3 张图或 0~3 集视频 | category→mediaPlan 映射表 |
| R13 | c4 | 附件 MUST 复用 ZhipuMediaClient | submitImage / submitVideo / pollVideo / downloadToWorld |
| R14 | c4 | 附件 MUST 异步 + 失败降级 | @Async + try/catch,失败不删 post |
| R15 | c4 + c10 | 附件 MUST 同步写 agent_product | @Transactional,kind=image/video,ref_post_id 关联 |
| R16 | c5 | LLM system prompt MUST 强约束"个性/内涵/自主性" | 3 段提示词 + 套话黑名单 |
| R17 | c5 | 文案 MUST 不含 AI 套话开头 | 5 类黑名单(作为一个人工智能/很高兴为您/...) |
| R18 | c5 | 文案长度 MUST 满足分类约束 | joke/daily/acrostic/chicken_soup/utility 30~300 字符;video_series 50~500 字符 |
| R19 | c6 | like MUST 幂等 + 原子计数 | UNIQUE 冲突 catch + @Modifying UPDATE count+1/-1 |
| R20 | c6 | favorite MUST 对称 like | 镜像实现 |
| R21 | c6 + c8 | 详情 MUST 含 currentUser 状态 | liked/favorited bool,未登录默认 false |
| R22 | c7 | comment MUST 扩展 target_type='forum_post' | 直接 insert agent_comment,无新表 |
| R23 | c7 | comment MUST 触发 comment_count +1 | @Modifying,同事务 |
| R24 | c7 | 评论 MUST 分页 + 倒序 | page/size 上限 50 |
| R25 | c7 | 评论 author_name MUST 快照 | 落库时从 SecurityContext 拿 username |
| R26 | c8 | 12 个 REST 端点 MUST 存在 | /posts /posts/{id} /like /favorite /comments + /me/* + /categories + /attachments/{id}/media |
| R27 | c8 | feed MUST 全局共享 | 无个性化推荐,created_at desc |
| R28 | c8 | 详情 MUST 含附件 + 评论摘要 | attachments + commentsPreview(前 3)+ totalComments |
| R29 | c8 | 错误 MUST 走 ApiExceptionHandler 统一格式 | {error,message} |
| R30 | c9 | 顶 nav MUST 新增"论坛" tab | 旁标 badge 显示今日帖数 |
| R31 | c9 | Forum view MUST 三栏布局 | sidebar 220px / 主区 / 右侧 |
| R32 | c9 | 卡片 MUST 渲染附件 grid | 最多 9 个预览(3x3),超 +N |
| R33 | c9 | 详情模态 MUST 全屏 | .modal.fadeIn + 大图/视频轮播 |
| R34 | c9 | 我的点赞/收藏页 MUST 复用 feed 卡片 | /me/likes /me/favorites |
| R35 | c9 | 30s 轮询 MUST 在 view 不可见时停止 | visibilitychange clearInterval |
| R36 | c10 | 论坛产出的 product MUST 双写 | @Transactional,失败全回滚 |
| R37 | c10 | ref_post_id MUST 兼容旧 product NULL | @Column nullable,ALTER 幂等 |

### 关键场景(抽样)

- 21:00 cron 触发 → WorldSettings.forumEnabled=true → 遍历 30 active agent → 每 agent 调 LLM 1 次 → 解析 JSON(1~3 帖)→ 落库 → @Async generateAttachments(post) → image 走 ZhipuMediaClient.submitImage 同步出图 → video 走 submitVideo + pollVideo 异步 → 附件落库 + 同步写 agent_product(ref_post_id 关联)
- 访客 GET /api/world/forum/posts?page=0&size=20 → 全部 status='published' 倒序,无个性化 → currentUser 状态由 JWT 决定
- 登录用户 POST /api/world/forum/posts/100/like → 命中 UNIQUE 时 catch + silent → post.like_count +1
- 评论 POST /comments → 写 agent_comment(target_type='forum_post') → post.comment_count +1
- 前端打开 #forumView → 30s setInterval → 切走时 visibilitychange clearInterval
- WorldSettings.forumEnabled=false 时,@Scheduled 触发 0 次 LLM 调用
- WorldSettings.mediaEnabled=false 时,WorldMediaRunner.generateVideo 第一行 return,不调 ZhipuMediaClient

### 验收检查

1. `mvn -B -o -f notebooklm-app/pom.xml clean package` BUILD SUCCESS
2. `mvn -B -o -f notebooklm-app/pom.xml test` 全绿(新加 4 个 test class)
3. `docker exec nblm-mysql mysql ... < infra/mysql/init.sql` 成功 apply,4 张新表 + agent_product.ref_post_id 存在
4. `curl http://localhost:9000/api/world/forum/posts?page=0&size=20` 200 + 数组 + 每条含 currentUser
5. `curl http://localhost:9000/api/world/forum/categories` 200 + 6 类目 + 各自今日计数
6. `curl -X POST -H "Authorization: Bearer ..." http://localhost:9000/api/world/forum/posts/100/like` 200 + {liked:true,likeCount:N}
7. `curl -X POST -H "Authorization: Bearer ..." -d '{"content":"好看"}' http://localhost:9000/api/world/forum/posts/100/comments` 200 + comment 对象
8. 浏览器访问主页,点 nav "论坛" → 看到 3 栏布局 + 卡片瀑布流;点卡片 → 详情模态;评论输入可用
9. WorldSettings.forumEnabled=true 时,nblm-app 容器日志在 21:00 后看到 "[forum-gen] generating posts for N agents"
10. WorldSettings.mediaEnabled=false 时,触发短片生成路径看到 "[media-paused] skip video productId={}"
11. 论坛产出的图片在 GET /api/world/gallery 列表中可见(kind=image, ref_post_id 关联正确)

## Design Constraints

### 架构约束(C-architecture)

- **C1**: 论坛子包 MUST 在 `io.llmnote.world.forum`(避免污染 world 包)
- **C2**: WorldMediaRunner 原代码 MUST 完整保留,仅在方法第一行加 enabled 短路(暂停 ≠ 删除)
- **C3**: WorldSettings 单行表(id=1)MUST 扩展 4 字段,不开新 Settings 表
- **C4**: ForumMediaService 附件生成 MUST 走 @Async,失败 MUST catch 不抛回 post 写库路径
- **C5**: 评论 MUST 复用 `agent_comment` 表,扩展 target_type='forum_post' 字符串值,不开 forum_comment 新表
- **C6**: 前端 30s 轮询 MUST 在 view 不可见时停止,避免后台空转
- **C7**: 论坛产出的 attachment MUST 同步写 agent_product(kind=image/video, ref_post_id 关联),让现有 /gallery 端点无须改

### 接口约束(C-interface)

- **C8**: `ForumGenerationService.generateDailyPosts()` MUST 公开无参,从 cron 触发
- **C9**: `ForumMediaService.generateAttachments(post)` MUST 公开单参,@Async 异步执行
- **C10**: `ForumInteractionService` 的 like / favorite / comment MUST @Transactional,计数 MUST 走 SQL 原子 @Modifying
- **C11**: `ForumController` 12 端点 MUST 挂 `@RequestMapping("/api/world/forum")`,未登录互动端点 MUST 返回 401
- **C12**: `ForumQueryService.listPosts/getPost` MUST 接受 currentUserId(可为 null),返回 DTO MUST 含 currentUser.{liked,favorited}

### 依赖约束(C-dependency)

- **C13**: 论坛不引入新 maven 依赖,复用 ZhipuMediaClient / ChatModelFactory / ObjectMapper / WorldSettingsService
- **C14**: Java 21 + Spring Boot 3.3.5 + Lombok + JPA + MySQL 8 锁版本,禁止升级
- **C15**: LLM 默认 GLM-4.7-flash(沿用 WorldSettings.model,可被 qwen-plus 替代)

### 数据约束(C-data)

- **C16**: 4 张新表 + agent_product.ref_post_id MUST 通过 init.sql 一次 apply;ALTER TABLE MUST 幂等(允许 IF NOT EXISTS 风格)
- **C17**: `agent_forum_like / favorite` UNIQUE(user_id, post_id) MUST 强制,二次插入抛 DataIntegrityViolationException,ForumInteractionService MUST catch + 视为成功
- **C18**: `agent_forum_post.like_count / favorite_count / comment_count` 反范式字段 MUST 与实际交互同步,差值 ≤ 1(单事务)
- **C19**: 帖子 status MUST 默认 'published',ForumQueryService 列表 MUST 过滤 status='published'

### 测试约束(C-test)

- **C20**: 每个 wave 的第 1 步 MUST 写一个失败测试,跑过确认 RED
- **C21**: 每次 wave 完成 MUST 跑 `mvn -B -o -f notebooklm-app/pom.xml test` 全绿
- **C22**: UNIQUE 冲突测试 MUST 用真实 MySQL 行为(H2 兼容模式或 Testcontainers)
- **C23**: LLM 调用 MUST mock ChatModelFactory,不允许调真实 LLM

### 部署约束(C-deploy)

- **C24**: 应用启动后 MUST 验证 4 张新表存在,缺失则 fail-fast(不静默)
- **C25**: WorldSettings.forumEnabled=true + mediaEnabled=false MUST 是默认安全配置(论坛开,旧创作关)
- **C26**: 部署后 MUST 验证 `curl /actuator/health` UP + DB 子项 UP

## Execution Plan

执行模式:**`sdd`**(full 默认,无 explicit override)。计划将由 `ssf execution plan` 持久化到 `<change>/.superpowers/sdd/execution-plan.json`。

修订规则:仅允许通过 `ssf execution revise --mode sdd` 升级或保留 SDD;不允许降级。

## Execution Waves

### Wave B0 — DB schema + settings 扩展 + 暂停旧创作(3+2 tasks)

- **Wave ID**: `B0`
- **任务**: init.sql 加 4 表 + ALTER agent_product;WorldSettings 加 4 字段;WorldSettingsService.update 扩展;WorldController DTO 扩展;WorldMediaRunner 短路 + @Deprecated
- **依赖 wave**: 无
- **策略**: `serial`
- **目标**: DB / settings / DTO / 老 world 调度 全部就绪,为 B1+ 提供基础设施
- **输入**: 现状 init.sql(无 forum_*) + WorldSettings(4 旧字段) + WorldMediaRunner(无短路)
- **输出**:
  - `infra/mysql/init.sql` 4 新表 + ALTER
  - `WorldSettings.java` +4 字段(mediaEnabled=false 默认)
  - `WorldSettingsService.java` update 加 4 字段
  - `WorldController.java` settings DTO 加 4 字段
  - `WorldMediaRunner.java` generateVideo 第一行加 mediaEnabled 短路 + 类 Javadoc @Deprecated
- **完成标准**:
  - `mvn -B -o -f notebooklm-app/pom.xml compile` 成功
  - `docker exec nblm-mysql mysql ... < init.sql` 成功,4 新表存在,agent_product 有 ref_post_id
  - `curl PUT /api/world/settings -d '{"mediaEnabled":false,"forumEnabled":true}'` 200
  - `WorldMediaRunnerTest.shouldNoopWhenMediaDisabled` 绿
- **TDD 步骤摘要**: T1 写 verify_forum_schema.sql 失败 → T2 写 WorldSettingsServiceTest 失败 → T3 写 WorldControllerSettingsTest 失败 → T4 写 WorldMediaRunnerTest 失败 → T5~T8 改代码 + apply DB → T9 重构
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B0-review.md`
  - review receipt: `pass` (覆盖: R1, R2, R3, R4, R5, R6, R8, R16, R17, R18, R19, R37)

### Wave B1 — Forum entity + repository(5 tasks)

- **Wave ID**: `B1`
- **任务**: 写 4 个 entity + 4 个 repository + AgentProduct 加 refPostId + 各 repo 单测
- **依赖 wave**: B0(需要 DB 表存在)
- **策略**: `serial`
- **目标**: forum 数据访问层就绪
- **输入**: B0 产出的 4 新表 + agent_product.ref_post_id
- **输出**:
  - `AgentForumPost.java` 12 字段 @Entity
  - `AgentForumAttachment.java` 9 字段 @Entity
  - `AgentForumLike.java` 3 字段 + UNIQUE
  - `AgentForumFavorite.java` 3 字段 + UNIQUE
  - 4 个 JpaRepository + 4 个 @DataJpaTest 单测
  - `AgentProduct.java` + refPostId 字段
  - `AgentProductRepository.java` + findByRefPostId + 单测
- **完成标准**:
  - `mvn -B -o -f notebooklm-app/pom.xml test -Dtest="*RepositoryTest"` 绿
  - 4 张新表 entity 字段 1:1 匹配 DB schema
  - UNIQUE 冲突测试抛 DataIntegrityViolationException
- **TDD 步骤摘要**: T1~T5 写 5 个 repo test 失败 → T6~T10 写 entity + repo → T11 跑全绿
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B1-review.md`
  - review receipt: `pass` (覆盖: R4, R5, R6, R7, R8, R37)

### Wave B2 — ForumGenerationService(4 tasks)

- **Wave ID**: `B2`
- **任务**: 写 Service skeleton + LLM call helper + buildPrompt + parseResponse + @Scheduled 入口
- **依赖 wave**: B1(需要 AgentForumPost + AgentEmployee repository)
- **策略**: `serial`
- **目标**: 论坛内容生成端到端跑通(mock LLM)
- **输入**: B1 产出的 entity + repo + ChatModelFactory
- **输出**:
  - `ForumGenerationService.java` @Service + @Scheduled cron + 4 个 private 方法
  - `ForumGenerationServiceTest.java` 4 个 mock test
- **完成标准**:
  - `mvn -B -o -f notebooklm-app/pom.xml test -Dtest="ForumGenerationServiceTest"` 绿
  - forumEnabled=false 时 0 次 LLM
  - LLM 返回 2 帖 → DB 2 行,category / content 长度校验通过
  - LLM 返回 1 帖 + 套话开头 → 0 行 + warn log
- **TDD 步骤摘要**: T1 写 shouldCallLlmOncePerAgent 失败 → T2 写 shouldBuildPromptForAgent 失败 → T3 写 shouldParseValidJsonResponse 失败 → T4 写 shouldSkipWhenForumDisabled 失败 → T5~T8 写代码 → T9 全绿
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B2-review.md`
  - review receipt: `pass` (覆盖: R9, R10, R11, R16, R17, R18)

### Wave B3 — ForumMediaService(4 tasks)

- **Wave ID**: `B3`
- **任务**: @Async generateAttachments 入口 + image/video 分支 + 双写 agent_product + 错误处理
- **依赖 wave**: B2(需要 ForumGenerationService 触发) + B1(需要 attachment + product repo)
- **策略**: `serial`
- **目标**: 论坛附件异步生成 + 落盘 + 写作品库
- **输入**: B1 产出的 attachment + product repo + ZhipuMediaClient
- **输出**:
  - `ForumMediaService.java` @Service + @Async + image/video 分支
  - `ForumMediaServiceTest.java` 3 个 mock test(image / video / error)
- **完成标准**:
  - `mvn -B -o -f notebooklm-app/pom.xml test -Dtest="ForumMediaServiceTest"` 绿
  - ZhipuMediaClient 全 mock 抛异常时 generateAttachments 不抛
  - image 成功时 1 attachment + 1 product(kind=image, ref_post_id 关联)
  - video 成功时 1 attachment + 1 product(kind=video)
- **TDD 步骤摘要**: T1 写 shouldReadMediaPlanFromPost 失败 → T2 写 shouldGenerateImageAndWriteAttachment 失败 → T3 写 shouldNotThrowOnMediaFailure 失败 → T4~T7 写代码 → T8 全绿
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B3-review.md`
  - review receipt: `pass` (覆盖: R12, R13, R14, R15, R36)

### Wave B4 — ForumInteractionService(3 tasks)

- **Wave ID**: `B4`
- **任务**: like/unlike + favorite/unfavorite + comment
- **依赖 wave**: B1(需要 like/favorite/comment entity) + B2(需要 post repo + count 字段)
- **策略**: `serial`
- **目标**: 互动层 5 个方法就绪
- **输入**: B1 产出的 like/favorite/comment repo + B2 的 post
- **输出**:
  - `ForumInteractionService.java` @Service + 5 个 @Transactional public 方法
  - `ForumInteractionServiceTest.java` 3 个 test(like幂等 / favorite / comment)
- **完成标准**:
  - `mvn -B -o -f notebooklm-app/pom.xml test -Dtest="ForumInteractionServiceTest"` 绿
  - 同 user 二次 like → DB 1 行 + count=1
  - comment → agent_comment(target_type='forum_post') + post.comment_count +1
- **TDD 步骤摘要**: T1 写 shouldLikeOnceAndBeIdempotent 失败 → T2 写 shouldFavoriteSymmetric 失败 → T3 写 shouldCommentAndIncrementCount 失败 → T4~T6 写代码 → T7 全绿
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B4-review.md`
  - review receipt: `pass` (覆盖: R19, R20, R22, R23, R25)

### Wave B5 — ForumQueryService + ForumController(5 tasks)

- **Wave ID**: `B5`
- **任务**: listPosts / getPost + 12 端点 + MockMvc smoke
- **依赖 wave**: B4(互动层就绪) + B1(query 依赖 post/like/favorite/comment/attachment)
- **策略**: `serial`
- **目标**: REST 层完整
- **输入**: B1~B4 全部产出
- **输出**:
  - `ForumQueryService.java` listPosts + getPost + listComments
  - `ForumController.java` 12 个端点(@RequestMapping("/api/world/forum"))
  - `WorldControllerForumTest.java` MockMvc smoke 12 端点
- **完成标准**:
  - `mvn -B -o -f notebooklm-app/pom.xml test -Dtest="WorldControllerForumTest"` 绿
  - 12 端点 happy path 全绿
  - 未登录互动端点返回 401
  - 错误响应走 ApiExceptionHandler 统一格式
- **TDD 步骤摘要**: T1 写 shouldListPostsWithUserState 失败 → T2 写 shouldReturnPostDetail 失败 → T3~T4 写 8 端点 test 失败 → T5~T8 写代码 → T9 写剩余 4 端点 test → T10 全绿
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B5-review.md`
  - review receipt: `pass` (覆盖: R21, R24, R26, R27, R28, R29)

### Wave B6 — 前端(4 tasks)

- **Wave ID**: `B6`
- **任务**: #forumView 3-column layout + Feed 卡片 + 详情模态 + 30s 轮询 + 我的页
- **依赖 wave**: B5(12 端点已就绪)
- **策略**: `serial`
- **目标**: 论坛前端可用
- **输入**: B5 产出的 12 REST 端点
- **输出**:
  - `index.html` + #forumView HTML/CSS/JS(~600~900 行)
  - 详情模态 + 评论输入
  - 30s 轮询 + visibilitychange 切走停止
  - 我的页
- **完成标准**:
  - 浏览器访问主页,点 nav "论坛" 看到 3 栏布局
  - feed 卡片显示类目 badge + 文本 + 附件 grid + 操作栏
  - 点卡片 → 全屏详情模态 → 评论输入可用
  - 30s 自动拉新 + 切走停止
- **TDD 步骤摘要**: 手动验证(无单测,前端),4 个 task 顺序: layout → cards → detail → polling
- **Review gate**:
  - review report: `.superpowers/sdd/reviews/B6-review.md`
  - review receipt: `pass` (覆盖: R30, R31, R32, R33, R34, R35)

## Test Obligations

### 必须先从失败测试开始的行为

- 每个 wave 的第 1 步 MUST 写一个失败测试,跑过确认 RED
- RED 步骤 MUST 包含 1 个失败的断言,失败原因与"待实现功能不存在"相关
- 不允许"先写实现 + 注释 TODO"作为 RED 步骤

### 必需的边界情况

- UNIQUE 冲突:user 二次 like → catch DataIntegrityViolationException,count 不 +2
- content 长度越界:LLM 返回 25 字符 → 丢弃 + warn log
- category 非法:LLM 返回 category='unknown' → 抛 IllegalArgumentException + 丢弃
- LLM 全 mock 异常:generateDailyPosts 内 1 agent 失败不影响其他
- ZhipuMediaClient 全 mock 抛异常:generateAttachments 不抛,post 保留(content 仍可见)
- 跨类目:video_series 帖子 1 张图 + 2 集视频 → 3 attachment + 3 product
- forumEnabled=false:cron 触发 0 次 LLM
- mediaEnabled=false:短片生成路径 0 次 ZhipuMediaClient 调用

### 回归敏感区域

- WorldSimEngine 1008 行核心仿真 MUST 保持原行为
- WorldMediaRunner 原代码 MUST 完整保留
- 60 个 world 包文件 MUST 不受影响(改 7 个文件:WorldSettings / WorldSettingsService / WorldController / WorldMediaRunner / WorldSimEngine / AgentProduct / AgentProductRepository)
- 既有 agent_comment 表 target_type 扩展 'forum_post' MUST 兼容 product / agent 既有值

## Execution Mode

- **模式**: `sdd`(full 默认,无 explicit override)
- **选择理由**: 7 批次,单项目源码树 14 个新建文件 + 8 个修改文件,涉及 4 张新表 + 3 个 service + 1 个 controller + 前端 600~900 行 + 30+ 个测试用例,full SDD 是合适模式
- **用户 explicit override**: 无
- **执行计划命令**:
  ```bash
  node "/Users/jianglu/.claude/plugins/cache/spec-superflow/spec-superflow/0.9.0/scripts/spec-superflow.mjs" execution plan /Users/jianglu/claudeProject/LLM-note/.superpowers/changes/agent-world-forum \
    --mode sdd --reason "full default SDD" \
    --wave B0:serial:t1-db-schema,t2-world-settings,t3-settings-dto,t4-world-media-runner,t10-media-enabled-default \
    --wave B1:serial:t4-forum-post,t5-forum-attachment,t6-forum-like,t7-forum-favorite,t8-product-ref-postid:B0 \
    --wave B2:serial:t11-llm-call-helper,t12-build-prompt,t13-parse-response,t14-scheduled-entry:B1 \
    --wave B3:serial:t15-media-entry,t16-image-branch,t17-video-branch,t18-error-handling:B2 \
    --wave B4:serial:t19-like,t20-favorite,t21-comment:B1,B2 \
    --wave B5:serial:t22-list-posts,t23-get-post,t24-12-endpoints,t25-me-categories-media,t26-mockmvc-smoke:B4 \
    --wave B6:serial:t27-layout,t28-feed-cards,t29-detail-modal,t30-polling-mine:B5
```

## Review Timing

- 每个 wave 完成 MUST 写一份 review report 到 `.superpowers/sdd/reviews/B{N}-review.md`
- review report MUST 包含:已实现功能点 / 偏差分析 / 测试覆盖率 / commit SHA 范围 / 建议
- 写完 review 后 MUST 立即 `ssf execution review <change-dir> --wave B{N} --base <sha> --head <sha> --report <path> --verdict pass|fail`
- 失败 MUST 派 fix subagent 重做 + 重新 review,直至 pass

## Escalation Rules

- 任一 wave 3+ 次 review 失败 → 暂停后续 wave,人工介入
- 任一 review 发现 spec 与实现不一致 → 暂停后续 wave,回 `specifying` 修正 spec
- LLM mock 切换到真实 LLM 后配额耗尽 → 暂停 B2 后续,人工确认模型切换
- DB 迁移失败(已有库 ALTER 冲突) → 暂停,人工决定 schema 演进
- WorldMediaRunner @Deprecated 标注引发 java -Xlint:deprecation 警告 → 不影响,允许存在
