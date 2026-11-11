# Execution Contract: forum-category-and-thumbs

## Workflow
- **Mode**: tweak
- **Plan revision**: 1 (sdd, single wave W1 serial)

## Scope fence
仅修改 `notebooklm-app/src/main/resources/static/index.html`:
- HTML 结构(`.forum-sidebar` 提到顶部变 `.forum-cat-bar`)
- CSS(`.forum-cat-bar` 横排 + `.forum-card.has-attachment` grid 80px 1fr)
- JS(`renderForumCard` 检测 attachments 切换布局类)

## Out of scope
- 后端 API / 实体 / 数据库
- 论坛详情模态
- 论坛轮询逻辑
- 点赞 / 收藏 / 评论 交互
- 群聊 / 智能体小镇 / 创作工作台 等其他模块

## Acceptance
1. 浏览器打开 `#forumView`,类目筛选条出现在**帖子列表上方**,横排 chip 样式
2. 点击「段子」「藏头诗」等类目,feed 正确过滤(`pickForumCategory` 行为不变)
3. 帖子有 attachments 时,卡片左侧出现 80x80 圆角缩略图,右侧文本布局不变
4. 帖子无 attachments 时,卡片布局与改动前完全一致
5. 移动端(< 768px)类目条横向滚动或自动折行,卡片缩略图不溢出
6. 控制台无 4xx / 5xx,无 JS 报错

## Rollback
`git revert` 当前 W1 commit + `docker compose build app && docker rm -f nblm-app && docker compose up -d app`

## Owner
蒋路 (开发) + 蒋路 (review)
