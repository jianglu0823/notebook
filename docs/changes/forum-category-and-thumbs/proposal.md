# Proposal: forum-category-and-thumbs

## 意图
把论坛类目筛选条从**左侧 sidebar** 改回**顶部横排**,并让帖子卡片在**有附件时**显示**左侧缩略图**。修复 B6 commit 改后用户反馈的 UX 退化。

## 用户原始反馈
> 之前类目是在上面,然后没有缩略图

## 范围(只改这两点,不动其他)

| # | 改动 | 文件 | 行数估算 |
|---|---|---|---|
| 1 | 把 `<aside class="forum-sidebar">` 改成顶部横排,占据 `.forum-shell` 全宽 | `index.html` HTML + CSS | ~30 行 |
| 2 | `renderForumCard` 在有 attachments 时,在卡片左侧加 80x80 缩略图(grid 布局) | `index.html` JS | ~25 行 |

## 不做的事
- 不改 forum API/后端
- 不改详情模态
- 不改轮询逻辑
- 不改点赞/收藏交互
- 不动 sidebar 在「我的点赞/收藏」视图时的位置(如果还在用)

## 验收
- [ ] 顶部横排:全部 / 段子 / 藏头诗 / 日常 / 鸡汤 / 视频连载 / 实用工具 / 我的点赞 / 我的收藏
- [ ] 点击切换类目,feed 内容正确过滤(已有 `pickForumCategory` 逻辑,只换位置)
- [ ] 有 attachments 的帖子,卡片左侧显示首张缩略图(80x80,圆角 8)
- [ ] 无 attachments 的帖子,卡片布局不变(全宽文本)
- [ ] 移动端 (< 768px) 类目条横向滚动或自动折行
- [ ] 浏览器硬刷新后生效,无 404 / 500

## 已知约束
- 只改前端 `notebooklm-app/src/main/resources/static/index.html`
- 不引入新依赖
- CSS 复用现有变量(`--panel`, `--line`, `--accent-weak`, `--radius-sm`)
- 缩略图用现有 `forumMediaUrl(id) + audioTokenQs()` 模式,与详情页缩略图一致
- 测试:不需要新单测(纯 CSS/HTML 布局改动),但需本地浏览器验证 + curl 抓 HTML 确认类目条已移位
