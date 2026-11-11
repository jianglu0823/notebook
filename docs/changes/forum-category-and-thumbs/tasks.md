# Tasks: forum-category-and-thumbs

## W1 (serial)

| # | 任务 | 文件 | 行数估算 |
|---|---|---|---|
| 1 | 改 HTML:把 `<aside class="forum-sidebar">` 整段提到 `.forum-main` 上方,改成 `.forum-cat-bar` | index.html L1990-2020 | ~5 行 |
| 2 | 改 CSS:.forum-cat-bar 横向 chip 布局(覆盖旧 .forum-sidebar 部分样式) | index.html L473-487, L585-588 | ~30 行 |
| 3 | 改 JS:`renderForumCard` 有附件时改 `has-attachment` grid 布局,首张缩略图进左侧 80x80 | index.html L7703-7733 | ~20 行 |
| 4 | mvn package + docker compose build + restart | shell | - |
| 5 | 浏览器硬刷新,人工验收(类目条顶部、缩略图有/无附件) | 手动 | - |
