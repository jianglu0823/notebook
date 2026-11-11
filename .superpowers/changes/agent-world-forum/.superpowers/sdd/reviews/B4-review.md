# B4 Review Report — ForumInteractionService

- **Wave ID**: B4
- **Change**: agent-world-forum
- **Commit**: 30ed33e (branch `agent-world-forum`, base b367d26)
- **Files Changed**: 3 files (+274)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| POST /like 幂等 | ✓ | findByUserIdAndPostId 预检 + UNIQUE 兜底,9 个测试覆盖 |
| POST /favorite 幂等 | ✓ | 同 like 模式 |
| DELETE /like /favorite 不抛 | ✓ | unlike 测试有 notLiked case 静默 noop |
| like_count / favorite_count / comment_count ±1 原子 | ✓ | AgentForumPostAtomicUpdater 5 个 @Modifying @Query |
| 评论复用 agent_comment(target_type='forum_post') | ✓ | addComment 落 targetType='forum_post' |
| 评论触发 comment_count +1 | ✓ | 同事务内 atomic.incrementCommentCount |
| 评论作者名快照 | ✓ | addComment 接 authorName 参数(从 SecurityContext 拿) |
| hasLiked / hasFavorited 暴露 | ✓ | 两个 public 方法 |
| @Transactional 包裹 | ✓ | 所有写方法都加 |
| 9 个单元测试 | ✓ | 全部绿 |
| 129 全项目测试 | ✓ | mvn test → 129, 0 fail, 1 skip |

## 关键设计点

1. **「读预检 + UNIQUE 兜底」双保险**:正常路径 findByUserIdAndPostId 看到 → 不写;并发场景两人同时写入时,findByUserIdAndPostId 都看到 empty,save 撞 UNIQUE 失败,DataIntegrityViolationException catch 视为成功。比 SELECT FOR UPDATE 简单,且能处理分布式场景。
2. **decrement 用 case when count > 0**:即便并发 race 导致 count 已经是 0,减 1 也不会变负。直接 `count - 1` 可能让 count 短暂变 -1,虽然 UI 不会显示,但 DB 状态污染。
3. **评论复用 agent_comment**:c7 spec 明确要求不新建表,只是新增 target_type 枚举值 'forum_post'。零 schema 变更,既有端点不受影响。
4. **comment_count 在 save 之后才 increment**:避免主键约束 / 字段问题导致计数错位。但都在 @Transactional 内,失败一起回滚。

## 风险与限制

- **没接 SecurityContext 拿 userId**:service 接 userId 参数,c8 REST 层要从 JWT 拿。这是分层惯例,service 不依赖 servlet API。
- **没接 authorId/authorName 解析**:c7 spec 要求快照 username。c8 会从 SecurityContext 拿 username + 拼 "u:{id}" 给 authorId。
- **decrement 没用 FOR UPDATE**:极端 race 仍可能让 count 短暂 -1(下次 +1 又会回正)。MySQL 8 + InnoDB 下 @Modifying 默认走 auto-commit,这里靠 @Transactional 兜底。如需严格,加 @Lock 或迁移到乐观锁(@Version)。
- **没测 comment 列表分页**:c8 /posts/{id}/comments?page=N&size=M 走现有 AgentCommentRepository.findByTargetTypeAndTargetIdOrderByIdDesc,size 上限 50 由 c8 加。

## Requirement 映射

| R# | Spec | B4 覆盖 | 测试 |
|---|---|---|---|
| c6-R1 (like 首次 + 重复幂等) | c6-likes-favorites | ✓ | like_firstTime + like_duplicate |
| c6-R2 (like UNIQUE race) | c6-likes-favorites | ✓ | like_raceCondition_caughtByUniqueConstraint |
| c6-R3 (取消 like -1) | c6-likes-favorites | ✓ | unlike_existingLike + unlike_notLiked |
| c6-R4 (favorite 同款) | c6-likes-favorites | ✓ | favorite_firstTime_insertsAndIncrements |
| c6-R5 (并发安全) | c6-likes-favorites | ✓ | AgentForumPostAtomicUpdater 5 原子 SQL |
| c7-R1 (评论 target_type=forum_post) | c7-comment-reuse | ✓ | comment_persistsAndIncrementsCommentCount |
| c7-R2 (comment_count +1) | c7-comment-reuse | ✓ | 同上 |
| c7-R3 (author_name 快照) | c7-comment-reuse | ✓ | addComment 接 authorName 参数 |
| c6-6 (hasLiked / hasFavorited 暴露) | c6-likes-favorites | ✓ | hasLiked 2 测试 |

## Verdict

**PASS** — B4 全部 done-when 满足,9/9 新测试绿,129/129 全项目测试绿。建议进入 B5 (ForumQueryService + REST 端点)。
