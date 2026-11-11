# c7: 评论复用 + 扩展

## ADDED Requirements

### Requirement: agent_comment.target_type MUST 支持 'forum_post'
`agent_comment` 表 `target_type` 字段是 VARCHAR(16),无须 schema 变更(已是 String)。新枚举值 `'forum_post'` MUST 允许与现有 `'product'` / `'agent'` 共存。

#### Scenario: 评论 forum_post
- WHEN POST /api/world/forum/posts/100/comments body={content:'好看'}
- THEN DB 插入 1 行 agent_comment(target_type='forum_post', target_id=100, author_id='u:16', author_name='username', content='好看')

#### Scenario: 既有 product 评论不受影响
- WHEN 现有 POST /api/world/comments body={targetType:'product', targetId:5, content:'赞'}
- THEN 行为与本期前一致,forum_post 扩展不影响

### Requirement: forum_post 评论 MUST 触发 comment_count 自增
创建 forum_post 评论时,`agent_forum_post.comment_count` MUST 同步 +1(在同事务内)。删除评论时 -1(本期不做删除,留 v2)。

#### Scenario: 评论触发计数
- WHEN 评论落库
- THEN post.comment_count + 1,响应 200 + {id, content, authorName, createdAt}

#### Scenario: 评论嵌套仅一级
- WHEN 评论 forum_post 的评论(parent_comment_id 不存在)
- THEN 一律 parent_comment_id=null,不做评论的评论(用户没要求)

### Requirement: 评论列表 MUST 分页 + 时间倒序
`GET /api/world/forum/posts/{id}/comments?page=0&size=20` MUST 返回 `{comments: [...], total: N, hasMore: bool}`,按 created_at desc 排序。size 上限 50。

#### Scenario: 100 条评论翻页
- WHEN post 有 100 条评论,GET page=0 size=20
- THEN 返回最近 20 条 + total=100 + hasMore=true

### Requirement: 评论作者信息 MUST 快照
`author_name` 在评论落库时 MUST 快照当时 JWT 中的 username(从 SecurityContext 拿),与论坛帖子的 author_name 处理一致。后续 author 改名不影响历史评论显示。

#### Scenario: 用户改名后
- WHEN user_id=16 在 t=0 发评论 "赞"(author_name='alice')
- AND t=1 用户改名为 'alice2'
- AND GET 评论列表
- THEN 该评论 author_name 仍显示 'alice',不是 'alice2'

## Out of Scope

- 不做评论的点赞
- 不做评论删除/编辑
- 不做 @mention
- 不做评论通知
