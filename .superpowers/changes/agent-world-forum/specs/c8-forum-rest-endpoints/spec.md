# c8: Forum REST 端点

## ADDED Requirements

### Requirement: WorldController MUST 新增 /api/world/forum/* 10 个端点
所有端点 MUST 走现有 JWT 鉴权(从 `AuthFilter` 注入 userId 到请求属性),无 token 时按现有规则处理(详情 / feed 可匿名,互动需登录)。

| 端点 | 方法 | 鉴权 | 描述 |
|---|---|---|---|
| `/api/world/forum/posts` | GET | 可选 | feed 列表,query: category, page, size |
| `/api/world/forum/posts/{id}` | GET | 可选 | 帖子详情,带附件 + 评论摘要(前 3 条)+ currentUser 状态 |
| `/api/world/forum/posts/{id}/like` | POST | 必登 | 点赞 |
| `/api/world/forum/posts/{id}/like` | DELETE | 必登 | 取消点赞 |
| `/api/world/forum/posts/{id}/favorite` | POST | 必登 | 收藏 |
| `/api/world/forum/posts/{id}/favorite` | DELETE | 必登 | 取消收藏 |
| `/api/world/forum/posts/{id}/comments` | GET | 可选 | 评论分页 |
| `/api/world/forum/posts/{id}/comments` | POST | 必登 | 发评论 |
| `/api/world/forum/me/likes` | GET | 必登 | 当前用户点赞过的帖子(分页) |
| `/api/world/forum/me/favorites` | GET | 必登 | 当前用户收藏过的帖子(分页) |
| `/api/world/forum/categories` | GET | 可选 | 6 类目清单 + 每个类目今日帖数 |
| `/api/world/forum/attachments/{id}/media` | GET | 可选 | 取附件 binary 流 |

#### Scenario: feed 列表匿名
- WHEN GET /api/world/forum/posts 无 JWT
- THEN 响应 200,每条 post 包含 currentUser={liked:false, favorited:false}

#### Scenario: feed 列表已登录
- WHEN GET 同 URL,带 JWT
- THEN 每条 post 包含 currentUser 真实状态

#### Scenario: 点赞未登录
- WHEN POST /like 无 JWT
- THEN 401,响应 `{error:'unauthorized'}`

### Requirement: feed 列表 MUST 全局共享
所有访客(登录/匿名)看到同一个 feed,排序 MUST 是 `created_at DESC`,无个性化推荐。

#### Scenario: 两个浏览器同看
- WHEN user_a 看 feed 第 1 页
- AND user_b 同时看 feed 第 1 页
- THEN 两条响应 posts 数组内容相同(无个性化)

#### Scenario: 翻页
- WHEN page=0 返回 [post#100, post#99, ..., post#81]
- AND page=1 返回 [post#80, ..., post#61]
- THEN 无重复,无遗漏

### Requirement: 详情 MUST 包含附件 + 评论摘要 + currentUser
`GET /api/world/forum/posts/{id}` 响应 MUST 包含:
- post 字段全量(id/author/category/title/content/counts/createdAt/status)
- `attachments: [{id, mediaType, filePath, seq, width, height, durationSec}]`
- `commentsPreview: [{id, authorName, content, createdAt}]` 前 3 条
- `currentUser: {liked, favorited}`
- `totalComments: N`

#### Scenario: 详情加载
- WHEN GET post_id=100 详情
- THEN 上述 4 个字段齐全,attachments 按 seq asc,commentsPreview 按 created_at desc

#### Scenario: 详情不存在
- WHEN GET post_id=99999
- THEN 404 + {error:'not_found'}

### Requirement: 端点错误响应 MUST 统一格式
所有 4xx/5xx MUST 走现有 `ApiExceptionHandler` 的统一格式 `{error: <code>, message: <text>}`。新增论坛端点 MUST 不引入新异常类型。

#### Scenario: 点赞已删除帖子
- WHEN POST /api/world/forum/posts/99999/like
- THEN 404 + {error:'not_found'}

## Out of Scope

- 不做"我的"页发帖 / 编辑 / 删除
- 不做搜索
- 不做热门排序
- 不做实时推送
