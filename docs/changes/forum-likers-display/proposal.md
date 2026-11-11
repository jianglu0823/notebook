# Proposal: forum-likers-display

## 意图
论坛帖子点赞区在 `likeCount` 数字之外,展示**最近 3 个点赞人的昵称**(类似 XHS 截图里的"爱空间-张静，陈娉"),让用户能直观看到"谁赞了"。

## 现状
- `AgentForumLike` 实体存 `userId / postId / createdAt`,但 API 响应不带 liker 列表。
- `ForumController.toPostMap` 只塞 `likeCount` 数字。
- 前端 `renderForumCard` 只渲染 `♥ N`。
- `lookupUserName(userId)` 已经能根据 `userId` 查到 `User.username`。

## 范围

| # | 改动 | 文件 |
|---|---|---|
| 1 | `AgentForumLikeRepository` 新增 `findByPostIdInOrderByPostIdAscIdDesc(List<Long>)`,一次拿 feed 里所有 post 的 likes(按 postId 分组后取每组前 3) | `AgentForumLikeRepository.java` |
| 2 | `ForumController.toPostMap` / `getPost` 加 `recentLikers: [{userId, username}]`(≤3 个,按时间倒序);list 路径用 `findByPostIdIn` 批量拉,避免 N+1 | `ForumController.java` |
| 3 | `renderForumCard` 在 `.forum-card-actions` 下方新增 `.forum-card-likers`:有 likers 时显示 "♡ 昵称1，昵称2，昵称3",超 3 个尾部加 "等 N 人";`renderForumDetail` 同步 | `index.html` |

## 不做的事
- 不改 schema / migration(数据已够)
- 不加"点赞人列表"弹层(本次只展示摘要)
- 不改 `quickLike` 交互
- 不改轮询(30s 拉一次自然带回来)
- 不动详情模态的"♥ N"按钮(只在卡片层加)

## 验收
- [ ] 列表 API 响应里 `recentLikers` 字段存在,长度 ≤ 3,按 `createdAt desc`
- [ ] 详情 API 响应里同样有
- [ ] 浏览器 feed 卡片:有 ≥1 个点赞时,底部出现 "♡ 张三，李四，王五" 区域;0 赞时不显示
- [ ] 赞超过 3 个时尾部加 "等 N 人"
- [ ] 点赞 / 取消点赞后,30s 内自然刷新(轮询)或下次进入页面看到正确昵称
- [ ] 浏览器控制台无 4xx / 5xx / JS 报错

## 已知约束
- 后端只读 `agent_forum_like` + `user` 两表,不引新依赖
- `User.username` 当前是唯一展示名;若日后引入 nickname 字段再切
- 测试:为 `recentLikers` 聚合逻辑加 1-2 个单测(覆盖 0/1/3/>3 边界)
