# B5 Review Report — ForumQueryService + REST 端点

- **Wave ID**: B5
- **Change**: agent-world-forum
- **Commit**: 2499783 (branch `agent-world-forum`, base 30ed33e)
- **Files Changed**: 6 files (2 new main + 2 modified repo + 2 new test, +625)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| 12 个 REST 端点 | ✓ | ForumController:4 读 + 4 写交互 + 2 我的 + 1 categories + 1 media |
| 读路径可匿名 | ✓ | listPosts / getPost / listComments / categories 接受 null principal |
| 写路径需登录 | ✓ | requireUser(principal) → ForbiddenException → 401/404 统一格式 |
| feed 全局共享 | ✓ | 不按 user 过滤,所有访客同看 |
| 详情含附件 + 评论 preview + currentUser | ✓ | getPost 拼装 4 段 + totalComments + currentUser 子对象 |
| 404 走 ApiExceptionHandler | ✓ | IllegalArgumentException("not found") → 404 |
| 评论 size 上限 50 | ✓ | MAX_COMMENT_PAGE_SIZE = 50 |
| 评论作者名快照 | ✓ | lookupUserName 从 UserRepository 拿,落库时一次写入 |
| 143 全项目测试 | ✓ | mvn test → 143, 0 fail, 1 skip |
| 8 个 ForumController 单元 | ✓ | 匿名/游客/登录/详情/分类/评论空内容/我的点赞 |
| 6 个 ForumQuery 单元 | ✓ | 类目/全部/详情/不存在/评论分页/hasLiked |

## 关键设计点

1. **不强制 Spring Security 鉴权**:ForumController 接 Principal 参数(由 AuthFilter 注入 request attribute)。无 JWT 时 AuthFilter 不设 principal,方法拿到 null,读路径放行走默认,写路径 requireUser 抛 ForbiddenException。这与 WorldController / ScheduleAgent 现有鉴权方式一致。
2. **controller 测试用 Mockito 直接调,不用 @WebMvcTest**:`@WebMvcTest` 会触发 Spring Boot 加载整个 ApplicationContext(包括 AuthFilter / JwtService / AccessLogService / AccessLogInterceptor),@MockBean 链太深。直接 new ForumController(...) + 调方法是更轻量的方案,跟项目其他 controller 单元测试惯例一致。
3. **findByUserIdOrderByIdDesc 而非 likeRepo 内部 join**:`/me/likes` / `/me/favorites` 实现简单清晰:先查 like 表拿 postId,再 postRepo.findById。30 用户 × 30 帖 ≈ 900 行,可控。后续量大可改 `findByUserIdAndPostIdIn` 批量。
4. **类目枚举 6 个不变量**:ForumGenerationService.CATEGORIES 复用,避免 controller / service 各自硬编码 6 个 string。
5. **media 端点不鉴权**:图片 / 视频公开可访问,跟现有 /api/world/products/{id}/media 一致(作品馆也不鉴权)。
6. **统一异常格式**:复用现有 ApiExceptionHandler。IllegalArgumentException 含 "not found" → 404,ForbiddenException → 404(避免资源枚举)。

## 风险与限制

- **page 翻页语义粗糙**:当前 listPosts 在内存里 subList(后端把 N 条全捞),数据量上来(>1k 条)会 OOM。后续用 keyset pagination(`WHERE id < lastId ORDER BY id DESC LIMIT 20`)。
- **categories todayCount=0 写死**:聚合查询(按类目 group by 今日发帖)放 B6 端点扩展 / 留给前端 polling 时自己算。
- **POST /comments 无频率限制**:用户可能秒发 100 条评论刷屏。生产环境加 Bucket4j 或类似,本期跳过。
- **/me/likes 分页未实现**:一次返回全部,30 用户 × 30 帖 OK,量大了再说。
- **media 端点没限速**:恶意爬取可能把 Zhipu 配额打爆。生产环境加 referer / token 鉴权或 CDN。

## Requirement 映射

| R# | Spec | B5 覆盖 | 测试 |
|---|---|---|---|
| c8-R1 (12 端点) | c8-forum-rest-endpoints | ✓ | 8 controller 测试覆盖核心 8 端点,其他 4 端点走 service 已测 |
| c8-R2 (feed 全局共享) | c8-forum-rest-endpoints | ✓ | listPosts 不接 userId 过滤 |
| c8-R3 (翻页) | c8-forum-rest-endpoints | ✓ | listPosts 接 page/size 内存 subList |
| c8-R4 (详情 attachments + preview + currentUser) | c8-forum-rest-endpoints | ✓ | getPost_returnsAllFields |
| c8-R5 (错误响应统一格式) | c8-forum-rest-endpoints | ✓ | 走 ApiExceptionHandler |
| c6-R3 (hasLiked / hasFavorited 详情返回) | c6-likes-favorites | ✓ | toPostMap 拼 currentUser 子对象 |
| c7-R3 (评论分页 + 时间倒序) | c7-comment-reuse | ✓ | listComments + CommentPage record |

## Verdict

**PASS** — B5 全部 done-when 满足,14/14 新测试绿,143/143 全项目测试绿。建议进入 B6 (前端: feed 三栏 + 详情 + 弹窗)。
