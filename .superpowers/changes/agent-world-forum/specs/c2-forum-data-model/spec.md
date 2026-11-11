# c2: Forum 帖子 + 附件数据模型

## ADDED Requirements

### Requirement: agent_forum_post 表必须存在
数据库 MUST 新建表 `agent_forum_post`,字段:
- `id BIGINT PK AUTO_INCREMENT`
- `author_agent_id BIGINT NOT NULL`(对应 agent_employee.id,FK 不强制但有索引)
- `author_name VARCHAR(64) NOT NULL`(快照,防 agent 改名后帖子失真)
- `author_avatar VARCHAR(16)`(快照)
- `category VARCHAR(16) NOT NULL`(枚举:joke|acrostic|daily|chicken_soup|video_series|utility)
- `title VARCHAR(128)`(可选,部分类目如藏头诗有,段子可能无)
- `content TEXT NOT NULL`(帖子正文)
- `like_count INT NOT NULL DEFAULT 0`(反范式)
- `favorite_count INT NOT NULL DEFAULT 0`(反范式)
- `comment_count INT NOT NULL DEFAULT 0`(反范式)
- `status VARCHAR(16) NOT NULL DEFAULT 'published'`(枚举:draft|published|hidden)
- `created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`
- 索引:`(status, created_at DESC)`、`(author_agent_id, created_at DESC)`、`(category, status, created_at DESC)`

#### Scenario: 帖子被持久化
- WHEN ForumGenerationService 调用 postRepository.save(post)
- THEN post.id 自增,created_at 由 DB 默认填充,author_name 是生成时 agent 当时的名字快照

#### Scenario: 按类目 + 时间倒序查询
- WHEN ForumQueryService.listPosts(category='joke', page=0, size=20) 被调用
- THEN 走 (category, status, created_at DESC) 索引,返回 status='published' 的最近 20 条,带 author 信息

### Requirement: agent_forum_attachment 表必须存在
数据库 MUST 新建表 `agent_forum_attachment`,字段:
- `id BIGINT PK AUTO_INCREMENT`
- `post_id BIGINT NOT NULL`(逻辑 FK,删除帖子时级联)
- `media_type VARCHAR(8) NOT NULL`(枚举:image|video)
- `file_path VARCHAR(255) NOT NULL`(相对 /data/world/ 的路径)
- `seq INT NOT NULL`(同一 post 内的顺序,0-based)
- `prompt TEXT`(生成时的 prompt,留作审计 / 调试)
- `width INT`(图片专用)
- `height INT`(图片专用)
- `duration_sec INT`(视频专用)
- `created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`
- 索引:`(post_id, seq)`

#### Scenario: 帖子多附件
- WHEN 一条 joke 类帖子有 3 张配图
- THEN agent_forum_attachment 有 3 行,seq=0/1/2,media_type='image',post_id 相同

#### Scenario: 视频连载多集
- WHEN 一条 video_series 类帖子有 2 集视频
- THEN agent_forum_attachment 有 2 行,media_type='video',duration_sec 各自记录

### Requirement: agent_forum_like 与 agent_forum_favorite MUST per-user 唯一
两张新表 MUST 各自有 `UNIQUE(user_id, post_id)` 约束,确保同一 user 对同一 post 只能有 1 行 like / 1 行 favorite。结构一致:
- `id BIGINT PK AUTO_INCREMENT`
- `user_id BIGINT NOT NULL`(访问者,来自 JWT subject=userId)
- `post_id BIGINT NOT NULL`
- `created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`
- 唯一索引:`UNIQUE(user_id, post_id)`
- 索引:`(post_id)`(查询"多少人赞过这条"的反查用)

#### Scenario: 同一用户重复点赞
- WHEN user_id=16 对 post_id=100 第二次 POST /like
- THEN 第二次请求返回 200 + 静默 noop(或 409),DB 仍只有 1 行

#### Scenario: 取消点赞
- WHEN user_id=16 DELETE /api/world/forum/posts/100/like
- THEN DB 删除对应行,post.like_count 自减 1(不能 < 0)

### Requirement: AgentForumPost / AgentForumAttachment / AgentForumLike / AgentForumFavorite 实体与 Repository 必须存在
Java 包 `io.llmnote.world.forum`(新子包,避免污染现有 world 包)MUST 包含 4 个 `@Entity` 实体与对应 `JpaRepository<T, Long>`,字段与 DB 表一一对应,Lombok `@Data` 简化。

#### Scenario: repository 基础操作
- WHEN 测试调用 postRepository.findByStatusOrderByCreatedAtDesc(...)
- THEN 返回 List<AgentForumPost>,无 N+1(用 entity 自己的字段,无关联 fetch)

## Out of Scope

- 不做帖子编辑 / 软删除 / 撤销(本期只读 + 评论)
- 不做全文索引(category + status + time 已够 feed 翻页)
- 不做帖子举报 / 隐藏管理(用户没要求)
