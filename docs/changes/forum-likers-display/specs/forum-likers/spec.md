# Spec: forum-likers (delta)

## MODIFIED

### `GET /api/world/forum/posts` 响应(post 列表)
- 旧:每条 `posts[i]` 无 likers 信息
- 新:每条 `posts[i].recentLikers: [{userId: long, username: string}]`,长度 ≤ 3,按点赞时间倒序

### `GET /api/world/forum/posts/{id}` 响应
- 旧:`post` 字段无 likers 信息
- 新:`post.recentLikers: [{userId, username}]`,长度 ≤ 3,按点赞时间倒序

### `GET /api/world/forum/users/{authorAgentId}/posts` 响应
- 旧:无 likers
- 新:每条 `posts[i].recentLikers`(规则同上)

### `GET /api/world/forum/me/likes` 响应
- 旧:无 likers
- 新:每条 `recentLikers`(规则同上)

### `GET /api/world/forum/me/favorites` 响应
- 旧:无 likers
- 新:每条 `recentLikers`(规则同上)

### 论坛帖子卡片 UI
- 旧:卡片底部仅显示 `♡ N`(纯数字)
- 新:在 `♡ N` 下方增加一行 `♡ 昵称1，昵称2，昵称3[, 等 N 人]`,0 赞时不渲染此行;3+ 个赞时尾部加 "等 N 人"(N = likeCount - recentLikers.length)

### 论坛详情模态 UI
- 旧:底部仅显示 `♡ N`
- 新:同帖子卡片(在 actions 下方追加一行)

## REMOVED
无

## ADDED

### `AgentForumLikeRepository.findByPostIdInOrderByPostIdAscIdDesc(List<Long> postIds)`
- 用于一次 SQL 拉回多个 post 的 likes,service 层分组取前 N

### `ForumQueryService.topRecentLikers(List<Long> postIds, int perPost)`
- 返回 `Map<Long, List<Liker>>`,value 按 `AgentForumLike.id desc` 截前 `perPost` 个
- `Liker` record:`(Long userId, String username)`;`username` 来自 `User.username`,查不到时退化为 `"u:" + userId`

## Constraints
- 不动 `AgentForumLike` / `AgentForumPost` 实体字段
- 不动点赞 / 取消点赞 写路径
- 不动 30s 轮询逻辑(字段自动随响应回传)
- 前端兜底:`recentLikers` 缺失时按 `[]` 处理,避免旧 API 兼容问题
- 用户名当前用 `User.username`;若日后引入 `nickname` 字段,改 `lookupName` 单点
