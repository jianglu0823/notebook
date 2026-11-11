# 03: 作者偶尔回复

## ADDED Requirements

### Requirement: After a comment is written, ForumInteractionService MUST publish CommentAddedEvent
`ForumInteractionService.addComment` MUST call `applicationEventPublisher.publishEvent(new CommentAddedEvent(postId, commentId, post.authorAgentId))` after the database write succeeds. If publishing throws, the exception MUST be caught + logged at warn level and MUST NOT propagate or change the return value.

#### Scenario: 评论成功写库
- WHEN addComment 写库成功
- THEN publishEvent 必被调用 1 次,事件中 postId/commentId/authorAgentId 都正确

#### Scenario: 评论写库失败
- WHEN addComment 抛异常
- THEN publishEvent 不被调用,异常正常往上抛

#### Scenario: publishEvent 失败
- WHEN publishEvent 自身抛异常
- THEN catch + log warn,addComment 的返回值仍是成功结果

### Requirement: AuthorReplyListener MUST be @Async and delay 5-30s before responding
`AuthorReplyListener` MUST be a `@Component`. Its `onCommentAdded(CommentAddedEvent)` MUST be annotated `@Async @EventListener` and MUST first call `ThreadLocalRandom.current().nextLong(minMs, maxMs)` and sleep, so the actual reply delay lands in the `author-reply.{min-delay-seconds, max-delay-seconds}` range (default 5-30s).

#### Scenario: 概率 = 0 时
- WHEN `author-reply.enabled=false` 或 `probability=0`
- THEN Listener Bean 不存在(@ConditionalOnProperty),事件发布后无副作用

#### Scenario: 概率 = 1 时
- WHEN `author-reply.probability=1.0`
- THEN 100% 触发回复(用 seeded random 验证)

#### Scenario: 概率 = 0.15 时
- WHEN 100 次 seeded random(种子固定)
- THEN 命中次数 must 在 [10, 20] 区间(允许 ±5 浮动)

### Requirement: After the delay, a probability roll MUST decide whether to actually reply
After the delay finishes, the listener MUST:
1. Fetch the latest comment for that post via `forumQueryService.listComments(postId, 0, 1)`.
2. Roll `ThreadLocalRandom.current().nextDouble() < probability`.
3. If the roll is **below** the threshold, return without action.
4. If the roll is **at or above** the threshold, call `authorReplyGenerator.generate(...)` for 1-2 sentences, then call `addComment(postId, authorAgentId, authorName, text)`.

#### Scenario: 命中回复
- WHEN 概率掷骰 < 阈值
- THEN addComment 被调用 1 次,authorAgentId = post.authorAgentId,文本非空

#### Scenario: 未命中跳过
- WHEN 概率掷骰 >= 阈值
- THEN addComment 不被调,日志 info "skipped, probability"

#### Scenario: 帖子已被删
- WHEN delay 期间 post 被 admin 删除(listPosts 返回空)
- THEN 跳过,addComment 不被调,log warn

#### Scenario: 作者 agent 变 inactive
- WHEN delay 期间 author agent.status 变 'inactive'
- THEN 跳过,addComment 不被调

### Requirement: No reply MUST be sent twice within a 5 minute cooldown for the same post
`AuthorReplyListener` MUST keep an in-memory `Map<Long, Long> lastReplyAtByPost` (postId → last reply timestamp ms). On a successful reply, the map MUST be updated to `now`. Before replying, if `now - lastReplyAt < cooldown-ms`, the listener MUST skip the reply.

#### Scenario: cooldown 期间二次事件
- WHEN 同一帖 1 分钟内连续来 3 个 CommentAddedEvent
- THEN 第一次正常回复(若掷中),第二/三次均因 cooldown 跳过

#### Scenario: cooldown 过期
- WHEN 同一帖 6 分钟后再来事件(cooldown = 5min)
- THEN 可以再次回复(若掷中)

#### Scenario: Listener 重启丢状态
- WHEN Listener 因 app 重启清空 Map
- THEN 重启后第一次事件允许回复(cooldown 是 best-effort,不持久化)

### Requirement: When enabled=false, the listener MUST not be registered
The listener bean MUST be registered only when `author-reply.enabled` is true (default), via `@ConditionalOnProperty(name="author-reply.enabled", havingValue="true", matchIfMissing=true)`. When `enabled=false`, the bean MUST NOT be created and the @EventListener MUST NOT subscribe.

#### Scenario: enabled=false
- WHEN `author-reply.enabled=false`
- THEN Listener Bean 不存在,@EventListener 不订阅,事件发布后无副作用

## Out of Scope

- 不做"作者每天最多 N 次回复"上限(下个 change 评估)
- 不持久化 cooldown Map(重启后失效,这是 acceptable)
- 不做"作者回复推送通知"(沿用 30s 轮询)
