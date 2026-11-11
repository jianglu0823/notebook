# B1 Review Report — Forum Entity + Repository

- **Wave ID**: B1
- **Change**: agent-world-forum
- **Commit**: 6ecbbe4 (branch `agent-world-forum`, base 1fc2108)
- **Files Changed**: 14 files (10 new + 2 modified main + 5 new tests)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| 4 个 forum 实体存在 | ✓ | AgentForumPost / AgentForumAttachment / AgentForumLike / AgentForumFavorite |
| AgentForumPost 11+ 字段对齐 DB schema | ✓ | 13 字段(id/authorAgentId/authorName/authorAvatar/category/title/content/likeCount/favoriteCount/commentCount/status/createdAt) |
| AgentForumPost 3 复合索引 | ✓ | @Index × 3: status+created / author+created / category+status+created |
| AgentForumAttachment 9 字段 + 索引 | ✓ | postId/mediaType/filePath/seq/prompt/width/height/durationSec/createdAt + idx_attach_post_seq |
| AgentForumLike UNIQUE(user_id, post_id) | ✓ | @Table(uniqueConstraints=@UniqueConstraint(columnNames={"user_id","post_id"})) |
| AgentForumFavorite 同款 UNIQUE | ✓ | 同上模式 |
| AgentProduct 加 ref_post_id | ✓ | @Column(name="ref_post_id") Long refPostId(nullable) |
| AgentProductRepository.findByRefPostId | ✓ | 派生方法,按 postId 查产品 |
| 单元测试覆盖 | ✓ | 7 个新测试(2 post + 2 attach + 1 like + 1 fav + 1 product) |
| 全项目测试通过(112/112) | ✓ | mvn test → Tests run: 112, Failures: 0 |
| 编译通过 | ✓ | mvn -o test 0 错 |

## 关键设计点

1. **新子包 `io.llmnote.world.forum`**:与 `io.llmnote.world` 平级,避免污染现有 world 包(沿用 design.md D5)。
2. **UNIQUE 约束由 DB 兜底**:Service 层 (B5) 处理 `DataIntegrityViolationException` 转 409/200 noop,这里只声明 schema。
3. **反范式计数**:post.likeCount / favoriteCount / commentCount 在 post 实体里,加 1/减 1 由 B5 维护,查询时 O(1) 不用 join。
4. **countByAuthorAgentIdAndCreatedAtAfter** 留作节流钩子:B3 cron 触发时检查"今天已发几条",超过 forumMaxPostsPerAgentPerDay 直接跳过。
5. **findByPostIdIn** 留作 feed 翻页回填:N+1 风险被批量查询化解(B6 用)。
6. **MySQL 关键字 'index' 改名**:复合索引命名用 `idx_post_*` 前缀,避免和 SQL 保留字冲突。

## 风险与限制

- **未应用 init.sql 到 nblm-mysql**:B0 同问题,沿用。本批所有 JPA 实体需要 4 张新表 + 1 个 ALTER,应用启动会因缺表失败。部署前必须跑 init.sql。
- **没加 H2 + @DataJpaTest**:本批用 Mockito 单元测试(Spring Data JPA 派生方法无 @Query,跑实际 SQL 边际效益低),与项目惯例一致。集成测试在 B6 收尾用 MockMvc 覆盖。
- **@Index 是 hint 而非 DDL**:JPA `ddl-auto: none` 下,这些 @Index 注解只在 init.sql 的 CREATE TABLE/ALTER 落地。代码层加了声明是给后人(及未来 ddl-auto: validate)对照阅读。

## Requirement 映射

| R# | Spec | B1 覆盖 | 测试 |
|---|---|---|---|
| c2-R1 (agent_forum_post 表 + 字段) | c2-forum-data-model | ✓ | AgentForumPostRepositoryTest |
| c2-R2 (按类目+时间倒序) | c2-forum-data-model | ✓ | findByStatusAndCategoryOrderByCreatedAtDesc |
| c2-R3 (agent_forum_attachment 表) | c2-forum-data-model | ✓ | AgentForumAttachmentRepositoryTest |
| c2-R4 (多附件 / 多视频) | c2-forum-data-model | ✓ | findByPostIdOrderBySeqAsc + findByPostIdIn |
| c2-R5 (like/favorite UNIQUE) | c2-forum-data-model | ✓ | AgentForumLikeEntityTest + AgentForumFavoriteEntityTest |
| c2-R6 (实体 + Repository) | c2-forum-data-model | ✓ | 7 个测试 + 5 个 Repository 派生方法 |
| c10-R1 (帖子→作品馆回流) | c10-output-to-gallery | ✓ | AgentProduct.refPostId + findByRefPostId |

## Verdict

**PASS** — B1 全部 done-when 满足,7/7 新测试绿,112/112 全项目测试绿,无编译错误。建议进入 B2 (暂停旧创作 — 实际 B0 已基本完成,B2 剩余清道任务)。
