# Design: forum-likers-display

## 改动 1:AgentForumLikeRepository 加批量查询

```java
List<AgentForumLike> findByPostIdInOrderByPostIdAscIdDesc(List<Long> postIds);
```

- 一次 SQL 拿 feed 列表里所有 post 的全部 likes,按 `post_id ASC, id DESC` 排序
- service 层按 `postId` 分组,每组取前 3

## 改动 2:ForumQueryService.topRecentLikers 聚合

```java
public Map<Long, List<Liker>> topRecentLikers(List<Long> postIds, int perPost) {
    if (postIds == null || postIds.isEmpty()) return Map.of();
    List<AgentForumLike> all = likeRepo.findByPostIdInOrderByPostIdAscIdDesc(postIds);
    Map<Long, List<Liker>> out = new LinkedHashMap<>();
    for (AgentForumLike l : all) {
        List<Liker> bucket = out.computeIfAbsent(l.getPostId(), k -> new ArrayList<>());
        if (bucket.size() < perPost) {
            bucket.add(new Liker(l.getUserId(), lookupName(l.getUserId())));
        }
    }
    return out;
}

public record Liker(Long userId, String username) {}
```

- `lookupName(userId)` 内部调 `userRepo.findById(userId).map(User::getUsername).orElse("u:"+userId)`
- 注入 `UserRepository` 到 `ForumQueryService`

## 改动 3:ForumController 五处灌入 recentLikers

`toPostMap(AgentForumPost, Long currentUserId, List<AgentForumAttachment>)` 改成 `toPostMap(..., List<Liker> recentLikers)`,并把 `recentLikers` 字段写进 map。

调用方(均需先 `topRecentLikers` 批量拉):
- `listPosts`(已有 `postIds` 在 `attachRepo.findByPostIdIn` 那个变量)
- `getPost`(单 post,调 `topRecentLikers(List.of(id), 3)`)
- `listUserPosts`(同 listPosts 模式)
- `myLikes`(返回的 like 列表查 postId 后批量拉)
- `myFavorites`(同 myLikes)

DTO 用 `ForumQueryService.Liker` record,直接序列化为 JSON `[{userId, username}, ...]`。

## 改动 4:index.html 渲染

`renderForumCard` 在 `</div><!--forum-card-actions-->` 后插入:

```html
${(p.recentLikers && p.recentLikers.length) ? `
  <div class="forum-card-likers">
    <span class="liker-heart">♡</span>
    ${p.recentLikers.map(l => escapeHtml(l.username)).join('，')}
    ${(p.likeCount > p.recentLikers.length) ? '，等 ' + (p.likeCount - p.recentLikers.length) + ' 人' : ''}
  </div>` : ''}
```

CSS:
```css
.forum-card-likers {
  font-size: 12px; color: var(--text-secondary);
  margin-top: 6px; padding: 4px 0;
  border-top: 1px dashed var(--line);
}
.forum-card-likers .liker-heart { color: #e84a5f; margin-right: 4px; }
```

`renderForumDetail` 同样在 actions 之后追加一行。

`quickLike` 切后:把刚点赞的用户名塞到 `post.recentLikers` 头部(若 `currentUser.username` 已知),取消点赞时从头部移除;`p.likeCount` 同步 +1/-1。轮询 30s 后服务端会拉直。

## 风险
- feed 列表可能拉回几十个 likes(若热帖),但有 idx_like_post 索引,OK
- `userRepo.findById` 在 service 层做 N 次(最坏每组 3 次),可优化为 `findAllById(userIdSet)` 一次查
