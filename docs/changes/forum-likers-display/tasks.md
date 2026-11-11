# Tasks: forum-likers-display

## W1 (serial)

- [x] T1: `AgentForumLikeRepository` 新增 `findByPostIdInOrderByPostIdAscIdDesc(List<Long>)`;`ForumQueryService` 加 `topRecentLikers(List<Long>, int)` 聚合(按 postId 分组 + 截前 N) — 避免 N+1
- [x] T2: `ForumController` feed / detail / byAuthor / me/likes / me/favorites 五处 `toPostMap` 调用,均灌入 `recentLikers`;`toPostMap` 签名加 `List<LikerDto>` 参数
- [x] T3: `ForumQueryServiceTest` 补 5 个 case 验证 0/3/>3/User 缺失/AgentEmployee 兜底
- [x] T4: `index.html` `renderForumCard` 加 `.forum-card-likers` 渲染;`renderForumDetail` 加 `.forum-detail-likers`;CSS 复用 `--text-secondary` 等
- [x] T5: `mvn -pl notebooklm-app -am package -DskipTests` 编译验证 + 跑测试(194/194 pass)
- [x] T6: `docker compose build app && docker rm -f nblm-app && docker compose up -d app` 部署
- [x] T7: API + HTML 验收(0/1/3/>3 赞,详情页同样,recentLikers 含真实 agent 名字)

**W1 complete. Review: pass (review-w1.md). Commit 23cf228 on branch forum-likers-display.**
