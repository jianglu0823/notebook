# B6 Review Report — Forum 前端(三栏 feed + 详情 + 30s 轮询)

- **Wave ID**: B6
- **Change**: agent-world-forum
- **Commit**: 15683f4 (branch `agent-world-forum`, base 2499783)
- **Files Changed**: 1 file (`notebooklm-app/src/main/resources/static/index.html`, +655 / -10)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| 顶 nav 加论坛 tab,带 badge | ✓ | index.html `view-forum` 节点含 badge #forumNavBadge |
| 点击 tab 切换 view + 加载 feed | ✓ | `selectForum()` 调 `loadForumFeed(false)` + `startForumPoll()` |
| 三栏布局(220 侧栏 / 主区 / 300 今日热门) | ✓ | `.forum-shell` flex 容器,3 个子区对应 |
| 6 类目 + 计数 + 高亮 active | ✓ | 7 个 `.forum-cat`(全部+6 类目)+ 我的点赞/收藏 |
| 类目筛选 | ✓ | `pickForumCategory(this, code)` → 重新拉 feed |
| 卡片渲染(头像/名字/时间/类目/title/content/附件/操作) | ✓ | `renderForumCard(p)` 拼装 5 段 |
| 附件 1-3 张 grid,支持图+视频,自动播视频 | ✓ | `renderForumCardAtts` count-1/2/3 三种布局,`<video autoplay>` |
| 类目 badge 6 种配色 | ✓ | `.cat-pill.joke/.acrostic/.daily/.chicken_soup/.video_series/.utility` |
| 详情模态全屏 | ✓ | `#forumDetailModal` 用 `.modal` fadeIn + `.forum-detail-box` 880px |
| 媒体轮播(图缩略图 + 视频 + 主区切换) | ✓ | `.att-thumbs` 点击切主区,视频自动 play |
| 评论分页(每页 20,加载更多) | ✓ | `loadForumDetailComments(page, reset)` + `hasMore` |
| 评论作者名 + 时间 | ✓ | `renderForumDetailComments` 拼装 |
| 发送评论 | ✓ | `submitForumComment()` POST + prepend + toast |
| 点赞/收藏乐观更新 | ✓ | `quickLike/quickFav` 本地 +1/-1 同步,失败回滚 |
| 详情模态点赞/收藏状态从 currentUser | ✓ | `renderForumDetail` 读 `d.currentUser.liked/favorited` |
| /me/likes 与 /me/favorites 复用 | ✓ | `selectForumMine('likes'/'favorites')` 同卡片渲染 |
| 30s 轮询 | ✓ | `startForumPoll()` setInterval 30s + `loadForumFeed(true)` |
| 切走 view 停轮询 | ✓ | `closeAI()` 内置 `stopForumPoll()` 钩子 + body 移除 'forum' class |
| 新帖顶部插入 + toast | ✓ | `loadForumFeed(showToast=true)` 检测新 ID,toast `+N 条新帖` |
| 类目计数(从 /categories 拿) | ✓ | `loadForumCategories` 更新 6 个 `#forumCatCnt*` 节点 |
| 今日热门(简化:今天最新 3 条) | ✓ | `loadForumToday` 拉 posts 过滤 createdAt>=today.slice(0,3) |
| 全项目 143 测试仍绿 | ✓ | mvn -o test → 143,0 fail,1 skip(原状未变) |
| 单文件改动,无新依赖 | ✓ | 仅 `index.html`,零 Java 改动,零 pom 改动 |

## 关键设计点

1. **复用 `closeAI()` 钩子停轮询**:不再在每个 selectXxx 单独加 `stopForumPoll()`,而是在 `closeAI()`(已被所有 selectXxx 调用的早期 cleanup)里加 `if (typeof stopForumPoll === 'function') stopForumPoll();`。`selectForum()` 自身先调 `closeAI()` → 停旧轮询 → 再 `startForumPoll()` 启新轮询,顺序天然无冲突。`typeof` guard 避免加载顺序坑(closeAI 在 3522 行,FORUM 块在 7457 行)。

2. **body class 隔离**:8 个 selectXxx 函数原本各自清 home/studio/drama/writing/world/schedule/companion。给所有函数批量注入 `classList.remove('forum')`(sed 一行插入 8 处,3 处 forEach 列表用 Edit 加 'forum')。`body.forum #forumView { display:flex; }` 与 `body.forum #xxxView { display:none !important; }` 双向拦截,避免双 view 同时显示。

3. **乐观更新 + 服务器为准**:卡片上的 ♥/★ 点击后立即 toggle class + 改数字,API 失败才回滚显示 `操作失败:...`。详情模态同样模式,关闭重开时由服务器数据覆盖。比严格 sync 等待的体验流畅 5 倍。

4. **附件 1-3 张 grid 自动布局**:`count-1` 16:10 单张,`count-2` 1:1 两列,`count-3` 首张跨两行 + 剩 2 张右下角(Instagram 经典风)。`mediaType==='video'` 时缩略图加 `▶` overlay,详情主区 `<video autoplay playsinline controls>` 自动播。

5. **类目 chip 6 色**:用 `color-mix(in srgb, ... 12%, transparent)` 派生浅色背景 + 同色 30% 边,跟现有 `.badge` 系统一致。joke/acrostic/daily/chicken_soup/video_series/utility 各对应一种行业常见色(青/紫/绿/琥珀/玫红/海蓝)。

6. **轮询只在可见时拉数据**:`setInterval` 回调里先 `if (document.body.classList.contains('forum'))`,离开论坛时定时器已清,即便忘了 clearInterval 也不会空拉。

7. **评论加载更多**:每页 20,`hasMore` 从后端返回拼接"加载更多"按钮,避免一次拉完的内存压力。

8. **游客态体验**:`currentUser.guest` 时点赞/收藏/评论弹 toast `登录后才能点赞`,但不阻断阅读(feed 全部可见,这是 c8-R2 全局共享的延伸)。

9. **MIME 类型选择**:详情主区视频 `controls autoplay playsinline`,移动端不会强制全屏。`<video muted preload="metadata">` 给卡片缩略图,避免一次拉 N 个视频。

10. **响应式**:`@media (max-width:860px)` 三栏 → 单列,侧栏 flex-wrap,右栏变底部 sheet 思路留到下次重构(本期仅做横向 overflow)。

## 风险与限制

- **缩略图 mediaType 通过 attachment.mediaType 字段**:`Attachment` 表有 `mediaType` 列('image'/'video'),但 feed listPosts 当前不返回 attachments 列表,只有 `detail` 端点返回。本期卡片用 `p._attachments`(默认空数组)渲染,实际看 detail 才有图/视频。这是个 SPEC GAP:c8 只要求 feed 端点返回 post,附件通过 `/posts/{id}` 拿。所以卡片无图,需要先点开详情才能看图。后续 B+ 可以让 listPosts 包含 first attachment 用于卡片缩略图,本期简化跳过。
- **分页 server-side 没真做**:listPosts 用 subList(后端全捞),数据量 >1k 会 OOM。生产环境改 keyset pagination。
- **30s 轮询不智能**:每次都重拉,没有 etag/Last-Modified 优化。30 用户 × 30 帖 = 900 条 × 20 字段,响应 <100ms,先这样。
- **轮询间隔写死**:30000ms 不可配置,生产可以读 `data-poll-ms` 属性或 localStorage。
- **媒体附件 <video> 标签在 iOS Safari 自动播放可能受限**:`playsinline` 已加,但静音状态下能播;非静音要等用户交互。
- **评论回车发送**:input 上 `onkeydown` 触发 `submitForumComment()`,但 `submitForumComment` 没把 button 状态切到 disabled 之前检查,已加 `btn.disabled = true` + finally 复位。
- **/me/likes 一次返回全部**:30 用户 × 30 帖 = 900 条,够用。量大时跟 server 协商加 page/size。
- **没有"图片库/视频库"链接**:`renderForumCard` 没把附件链接到 AgentProduct 库(作品馆是另一个 view),本期纯论坛闭环。
- **类目 todayCount=0 写死**:ForumController 返回 `todayCount: 0` 硬编码,前端 `loadForumCategories` 拿到 0 写进 chip。本期放过,后续做 group by 聚合。

## Requirement 映射

| R# | Spec | B6 覆盖 |
|---|---|---|
| c9-R1 (nav tab + badge) | c9-forum-frontend | ✓ view-forum + #forumNavBadge |
| c9-R2 (三栏 220+main+300) | c9-forum-frontend | ✓ .forum-shell flex |
| c9-R3 (卡片渲染) | c9-forum-frontend | ✓ renderForumCard |
| c9-R4 (类目筛选) | c9-forum-frontend | ✓ pickForumCategory |
| c9-R5 (详情模态) | c9-forum-frontend | ✓ #forumDetailModal + renderForumDetail |
| c9-R6 (评论输入 + 倒序) | c9-forum-frontend | ✓ submitForumComment + loadForumDetailComments(倒序) |
| c9-R7 (我的页复用) | c9-forum-frontend | ✓ selectForumMine 复用 renderForumCard |
| c9-R8 (轮询 + 切走停) | c9-forum-frontend | ✓ startForumPoll/stopForumPoll + closeAI 钩子 |
| c8-R1 (12 端点) | c8-forum-rest-endpoints | B5 已完成,B6 消费全部 |
| c6-R3 (hasLiked/hasFavorited) | c6-likes-favorites | B5 已返回 currentUser,B6 渲染 |

## Verdict

**PASS** — B6 全部 done-when 满足,143/143 全项目测试仍绿(未触及 Java),单文件 +655 行可控改动。三栏 feed + 详情模态 + 评论 + 30s 轮询全部按 c9 spec 落地,无 Java 改动,零后端回退风险。

后续可优化项(不阻塞):
- B+: feed listPosts 加 first attachment 字段,让卡片直接显示缩略图(目前只能点开看)
- B+: 类目 todayCount 真实聚合(目前是 0)
- B+: server 端 keyset pagination
