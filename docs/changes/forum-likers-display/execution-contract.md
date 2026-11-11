# Execution Contract: forum-likers-display

## Workflow
- **Mode**: tweak
- **Plan revision**: 1 (sdd, single wave W1 serial)

## Scope fence
- 后端:`AgentForumLikeRepository` 加 1 个查询;`ForumQueryService` 加 1 个聚合方法;`ForumController` 五处 `toPostMap` 调用灌入 `recentLikers`。
- 前端:`index.html` `renderForumCard` / `renderForumDetail` 加 likers 行 + 配套 CSS。
- 测试:为聚合逻辑补 1-2 个边界用例。

## Out of scope
- 论坛 API 写路径(点赞/收藏/评论)逻辑
- `AgentForumLike` / `AgentForumPost` 实体结构 / 字段 / migration
- 论坛详情模态里的"♥ N"按钮交互
- 论坛 30s 轮询逻辑
- 其他模块(创作 / 群聊 / 智能体小镇 / 笔记)

## Acceptance
1. feed 列表(`GET /api/world/forum/posts`)响应里,每条 `posts[i].recentLikers` 存在,长度 ≤ 3,按 `createdAt desc`,username 来自 `User.username`
2. 详情(`GET /api/world/forum/posts/{id}`)响应里 `post.recentLikers` 同样规则
3. 浏览器 feed 卡片:有 ≥1 个点赞时,卡片底部出现 "♡ 昵称1，昵称2，昵称3" 行;3+ 个时尾部 "等 N 人";0 个赞时该行不渲染
4. 详情模态底部同样渲染一行
5. 点赞 / 取消点赞后,30s 轮询或下次进入,昵称随 `likeCount` 同步刷新
6. 控制台无 4xx / 5xx / JS 报错
7. `mvn test` 全绿,新增 / 既有 0/1/3/>3 边界用例均通过

## Rollback
`git revert` W1 commit + `docker compose build app && docker rm -f nblm-app && docker compose up -d app`。
`recentLikers` 字段缺失时,前端用 `?? []` 兜底,不会因字段缺失炸页面。

## Owner
蒋路 (开发 + review)
