# 01: 居民随机互动

## ADDED Requirements

### Requirement: ResidentInteractionJob MUST run every 5-15 minutes
`ResidentInteractionJob` MUST be a Spring `@Component`,using `@Scheduled(fixedDelay = 5min, initialDelay = 60s)`. The `tick()` method MUST first call `ThreadLocalRandom.current().nextLong(minMs, maxMs)` and sleep so the actual interval lands in the `resident-interaction.{min-interval-minutes, max-interval-minutes}` range (default 5-15min). `fixedDelay` mode MUST prevent overlap.

#### Scenario: 跑批间隔落在配置区间
- WHEN 配置 `min-interval-minutes=5, max-interval-minutes=15`
- THEN 连续 100 次 `tick()` 之间的间隔 must 全部落在 [5min, 15min] 区间内(用 seeded random 验证)

#### Scenario: 配置 enabled=false 时不跑
- WHEN `resident-interaction.enabled=false`
- THEN `@Scheduled` 仍触发,但 `tick()` 第一行就 return,日志 info 一行 "skipped, enabled=false"

#### Scenario: 上一轮未完不重叠
- WHEN 一次 `tick()` 跑了 10 分钟(超过 fixedDelay 5min)
- THEN Spring 不会并发起新 `tick()`,上一轮结束后才开始下一轮

### Requirement: Candidate posts MUST be published posts from the last 24h
`tick()` MUST call `forumQueryService.listPosts(null, 0, 30)` and filter to `createdAt >= now - 24h` AND `status='published'`. If the filtered list has < 1 post, the tick MUST return immediately without side effects.

#### Scenario: 24h 内无帖
- WHEN 最近 24h 没有任何 published 帖
- THEN `tick()` 不调任何 Service,日志 info "no candidate posts"

#### Scenario: 24h 内 12 帖
- WHEN 最近 24h 有 12 帖
- THEN 候选列表 = 12 帖,后续随机抽 1-3 个从这里取

#### Scenario: 候选不足 1 个 agent
- WHEN 当前 active agent 数量 - 1(作者) = 0
- THEN 该帖直接跳过,不影响其他帖

### Requirement: Each candidate post MUST pick 1-2 non-author agents
For each candidate post the system MUST call `agentEmployeeRepo.findByStatus('active')`, exclude the post's `authorAgentId`, and randomly pick `min(candidates, max-agents-per-post)` agents (default 2). If zero non-author agents remain, the post MUST be skipped.

#### Scenario: agent 候选 = 5
- WHEN 某帖有 5 个非作者 active agent
- THEN 随机抽 2 个(由 `max-agents-per-post=2` 限制),记为 [A, B]

#### Scenario: agent 候选 = 1
- WHEN 某帖有 1 个非作者 active agent
- THEN 抽这 1 个

#### Scenario: agent 候选 = 0
- WHEN 没有非作者 active agent(全部作者或不在线)
- THEN 跳过该帖

### Requirement: Each agent MUST roll independent dice for like/favorite/comment
For each selected agent the system MUST roll three independent dice using `ThreadLocalRandom`:
- `roll < 0.5` → call `forumInteractionService.like(agentUserId, postId)`
- `roll < 0.3` → call `forumInteractionService.favorite(agentUserId, postId)`
- `roll < 0.6` → call `residentCommentGenerator.generate(...)` to get comment text → `forumInteractionService.addComment(postId, agentId, agentName, text)`

The three actions MUST be independent; 0/1/2/3 of them may fire. Any individual action failure MUST be caught + logged and MUST NOT affect the other actions.

#### Scenario: 全部命中
- WHEN seeded random 三个骰子都 < 阈值
- THEN like / favorite / addComment 都被调用 1 次,顺序无关

#### Scenario: 全部未命中
- WHEN seeded random 三个骰子都 >= 阈值
- THEN 三个 Service 一个都不被调

#### Scenario: like 失败
- WHEN `forumInteractionService.like` 抛异常
- THEN 异常 catch + log warn,favorite / addComment 继续调,后续 agent 继续

#### Scenario: 重复点赞跳过
- WHEN 候选 agent 已经 `currentUser.liked=true`(已赞过该帖)
- THEN 跳过 like 动作,favorite / addComment 仍执行

### Requirement: Full tick MUST finish in under 5 minutes
`fixedDelay` scheduling MUST defer the next tick if a tick runs long. Every blocking call inside `tick()` MUST have a timeout (LLM 8s, Service 3s); on timeout the action MUST be skipped for that agent.

#### Scenario: LLM 超时
- WHEN `residentCommentGenerator.generate` 8s 仍没返回
- THEN 中断等待,该 agent 的 comment 动作跳过,log warn "comment gen timeout"

## Out of Scope

- 不做"agent 偏好"个性化(每帖每人都按相同概率)
- 不做"评论质量评分"
- 不做"被赞多了就少赞"的反刷量机制(下个 change 评估)
