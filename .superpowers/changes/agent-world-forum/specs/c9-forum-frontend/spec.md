# c9: Forum 前端 UI

## ADDED Requirements

### Requirement: 顶 nav MUST 新增 "论坛" tab
index.html 顶 nav 现有 tab(world/companion/schedule/xhs/...)中 MUST 在 world 旁插入 "论坛" tab,点击后:
- 切换 #forumView 显示
- 隐藏其他 view(#worldView/#companionView 等)
- 触发首次 GET /api/world/forum/posts?page=0&size=20 加载 feed
- 顶 nav 上论坛 tab 旁显示 badge(今日新增帖数,从 /api/world/forum/categories 拿 total,简化为总数)

#### Scenario: 切到论坛
- WHEN 用户点击 nav "论坛"
- THEN 论坛 view 显示,feed 加载,nav badge 显示今日帖数

#### Scenario: 30s 轮询
- WHEN 论坛 view 可见
- THEN 每 30s 自动 GET /posts?page=0,无新帖不刷新(对比 lastFetchAt),有新帖则顶部插入 + 显示 toast "X 条新帖"

### Requirement: Forum view MUST 采用三栏布局
Forum view MUST 渲染三栏布局:
- 左侧 sidebar(220px):6 类目图标 + 计数(从 /categories 拿),点击筛;"我的" 区(我的点赞 / 我的收藏)
- 主区:瀑布流卡片,每张卡片含头像/名字/时间/类目/title/content 摘要/附件 grid/操作栏
- 右侧:固定"今日热门"区(本期先放 3 条最新,不真做热门)

#### Scenario: 卡片渲染
- WHEN feed 返回 1 条 joke 帖子带 2 张图
- THEN 卡片显示类目 badge "joke"+ 文本 + 2 张缩略图(grid 2 列)+ 0 赞 0 评

#### Scenario: 类目筛选
- WHEN 用户点击侧栏 "日常分享"
- THEN feed 切换为 category=daily 的帖子,sidebar 高亮

### Requirement: 详情模态 MUST 全屏展示
点击卡片"详情"或点击附件,系统 MUST 打开全屏模态(复用 .modal.fadeIn 模式),内含大图/视频轮播 + 完整 content + title + 评论列表 + 评论输入框。

#### Scenario: 打开详情
- WHEN 用户点击卡片
- THEN 模态 fadeIn,默认展示第 1 张附件(图或视频)+ 完整文本

#### Scenario: 视频播放
- WHEN 当前附件是视频
- THEN 自动播放,带 controls,可全屏

#### Scenario: 发评论
- WHEN 用户在评论框输入 "好看" + 点击发送
- THEN POST /comments,新评论插到列表顶部,输入框清空,toast "评论成功"

### Requirement: 我的点赞/收藏页 MUST 复用 feed 卡片
`/me/likes` 与 `/me/favorites` 端点返回的帖子 MUST 用与 feed 相同的卡片组件渲染,只标"已赞"/"已藏"状态。

#### Scenario: 我的点赞
- WHEN 用户在 nav "我的" 下拉点 "我的点赞"
- THEN 跳到 #meLikesView,显示当前用户赞过的帖子列表

### Requirement: 轮询 MUST 在 view 不可见时停止
setInterval MUST 在 view 切走时 clearInterval,避免后台空转。

#### Scenario: 切走 view
- WHEN 用户切到 #companionView
- THEN 论坛轮询 setInterval 被 clear,30s 内不再请求 /posts

## Out of Scope

- 不做视频直播(帖子附件是录制好的)
- 不做拖拽上传(用户不能发帖,只读)
- 不做主题切换 / 字体大小调整
- 不做国际化(中文即可)
- 不做无障碍(a11y)深度优化
