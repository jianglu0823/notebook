# Spec: forum-ui-layout (delta)

## ADDED

### 类目条位置
**论坛页面**(`#forumView`)的类目筛选条(全部 / 段子 / 藏头诗 / 日常 / 鸡汤 / 视频连载 / 实用工具 / 我的点赞 / 我的收藏)渲染在 feed 列表**上方**,横向 chip 布局,占满 `.forum-shell` 全宽。点击切换类目时调用现有 `pickForumCategory()`,行为不变。

### 帖子卡片缩略图
**论坛帖子卡片**(`renderForumCard` 输出)在帖子**有 attachments** 时,卡片布局切换为 `display: grid; grid-template-columns: 80px 1fr`,左侧 80x80 圆角缩略图(取 attachments 第一张 `image` 或 `video` 的封面),右侧保留原有 head / title / content / actions。无 attachments 时,布局不变(纯文本)。

## MODIFIED
无

## REMOVED
- 删除 `.forum-sidebar` 容器,合并到 `.forum-cat-bar`(顶部)
- 删除 `.forum-sidebar-hd` 块级标题(类目自带文字)

## Constraints
- 缩略图 URL 沿用 `forumMediaUrl(att.id) + audioTokenQs()`,与详情页缩略图保持一致
- 不引入新依赖,不改后端 API
- 移动端:类目条 `overflow-x: auto` + `flex-wrap: wrap`;卡片缩略图在窄屏自动降到 60x60(可选)
- 与现有 CSS 变量(`--panel`, `--line`, `--accent-weak`, `--radius-sm`)保持一致
