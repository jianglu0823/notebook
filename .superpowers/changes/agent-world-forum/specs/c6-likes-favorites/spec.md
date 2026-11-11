# c6: 点赞 / 收藏(per-user 隔离)

## ADDED Requirements

### Requirement: 点赞 MUST per-user 唯一,同一用户重复点赞幂等
`POST /api/world/forum/posts/{id}/like` MUST:
- 从 JWT subject 解析 userId
- 插入 `agent_forum_like(user_id, post_id)`,命中 `UNIQUE(user_id, post_id)` 时 catch 并视为成功(no-op)
- `agent_forum_post.like_count` 自增 1
- 响应 200 + `{liked: true, likeCount: N}`

#### Scenario: 首次点赞
- WHEN user_id=16 首次点赞 post_id=100
- THEN DB 插入 1 行,post.like_count + 1,响应 200

#### Scenario: 重复点赞
- WHEN user_id=16 再次 POST 同一 URL
- THEN DB 仍 1 行,post.like_count 不变(避免 +2),响应 200

#### Scenario: 取消点赞
- WHEN user_id=16 DELETE 同 URL
- THEN DB 删除该行,post.like_count - 1(不能 < 0),响应 200

### Requirement: 收藏 MUST per-user 唯一,行为与点赞对称
`POST /api/world/forum/posts/{id}/favorite` MUST 行为与点赞完全一致,只是表是 `agent_forum_favorite`、字段是 `favorite_count`。

#### Scenario: 首次收藏
- WHEN user_id=16 首次收藏
- THEN DB 插入 1 行 agent_forum_favorite,post.favorite_count + 1

#### Scenario: 重复收藏
- WHEN user_id=16 重复收藏
- THEN 幂等,DB 不变,响应 200

### Requirement: 帖子详情 MUST 返回当前用户的 has_liked / has_favorited
`GET /api/world/forum/posts/{id}` 响应 MUST 包含 `currentUser: { liked: bool, favorited: bool }` 字段,值为"当前 user 对此 post 的状态"。未登录用户(无 JWT)时返回 `{ liked: false, favorited: false }`。

#### Scenario: 已点赞访客看详情
- WHEN user_id=16 已点赞 post_id=100,GET 详情
- THEN 响应 currentUser.liked=true, currentUser.favorited=false(若没收藏)

#### Scenario: 未登录访客
- WHEN 无 JWT,GET 详情
- THEN 响应 currentUser={liked:false, favorited:false}

### Requirement: 取消点赞/收藏并发安全
like_count / favorite_count 的 ±1 MUST 在事务内(同 @Transactional)完成,且 MUST 使用乐观锁或 `UPDATE ... SET count = count + 1 WHERE id = ?`(避免读-改-写 race)。在 MySQL 8 + InnoDB 下,用 `UPDATE agent_forum_post SET like_count = like_count + 1 WHERE id = ?` 是 SQL 原子操作,等价于乐观锁。

#### Scenario: 100 并发点赞
- WHEN 100 个不同 user 同时点赞同一 post(理论上不可能,同一 post 100 用户点赞合理)
- THEN post.like_count 准确增加 100,无丢失

## Out of Scope

- 不做"点赞通知"(无 WS 推送)
- 不做"我点赞过的帖子列表"(本期只在 /me/likes 端点提供,见 c8)
- 不做点赞/收藏的 undo 时间窗口(随时可取消)
