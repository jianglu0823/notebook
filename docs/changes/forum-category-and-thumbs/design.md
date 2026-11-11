# Design: forum-category-and-thumbs

## Layout change

### Before
```
┌──────────────────────────────────────────────────────────┐
│ .forum-shell (flex row)                                  │
│ ┌────────────────┐  ┌──────────────────────────────────┐ │
│ │ forum-sidebar  │  │ forum-main                       │ │
│ │ 类目            │  │ toolbar (title + 刷新)            │ │
│ │ · 全部          │  │ ┌──────────────────────────────┐ │ │
│ │ · 段子          │  │ │ forum-card                  │ │ │
│ │ · 藏头诗        │  │ │   avatar · name · cat-pill  │ │ │
│ │ · 日常          │  │ │   title (optional)          │ │ │
│ │ · 鸡汤          │  │ │   content                   │ │ │
│ │ · 视频连载      │  │ │   (3 张缩略图横排,如有)       │ │ │
│ │ · 实用工具      │  │ │   ♡ ★ 💬                    │ │ │
│ │ ─ 我的 ─        │  │ └──────────────────────────────┘ │ │
│ │ · 点赞          │  │                                  │ │
│ │ · 收藏          │  │                                  │ │
│ └────────────────┘  └──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### After
```
┌──────────────────────────────────────────────────────────┐
│ .forum-shell (单列)                                      │
│ ┌──────────────────────────────────────────────────────┐ │
│ │ forum-cat-bar (横向滚动/折行)                          │ │
│ │ [全部 5] [段子 1] [藏头诗 0] [日常 2] [鸡汤 0] [视频 1] │ │
│ │ [实用 1]   |   [👍 点赞] [⭐ 收藏]                     │ │
│ └──────────────────────────────────────────────────────┘ │
│ ┌──────────────────────────────────────────────────────┐ │
│ │ forum-main                                           │ │
│ │ toolbar (title + 刷新)                                │ │
│ │ ┌──────────────────────────────────────────────────┐ │ │
│ │ │ forum-card (有附件时 layout: 缩略图 | 文本)         │ │ │
│ │ │ ┌──────┐  avatar · name · cat-pill                │ │ │
│ │ │ │ 80x80│  title (optional)                        │ │ │
│ │ │ │ thumb│  content                                │ │ │
│ │ │ └──────┘  ♡ ★ 💬                                 │ │ │
│ │ └──────────────────────────────────────────────────┘ │ │
│ │ ┌──────────────────────────────────────────────────┐ │ │
│ │ │ forum-card (无附件,沿用原布局,全宽文本)              │ │ │
│ │ │   avatar · name · cat-pill                        │ │ │
│ │ │   title (optional)                                │ │ │
│ │ │   content                                         │ │ │
│ │ │   ♡ ★ 💬                                          │ │ │
│ │ └──────────────────────────────────────────────────┘ │ │
│ └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

## CSS 关键改动

| 旧 class | 新 class | 改动 |
|---|---|---|
| `.forum-shell` flex row | `.forum-shell` flex column | 让 sidebar 自然变成顶部 |
| `.forum-sidebar` 280px fixed | `.forum-cat-bar` 横向 | 横排容器,overflow-x:auto |
| `.forum-sidebar-hd` 块级标题 | 删掉,合并到 chip 自身 | 类目标签自带文字 |
| `.forum-cat` 块级 | `.forum-cat` inline-flex,白底圆角胶囊 | chip 样式 |
| `.forum-card` 单列 | `.forum-card.has-attachment` 改 grid 80px 1fr | 有附件时左侧 80x80 |

## JS 关键改动

`renderForumCard(p)`:
- 现有逻辑:`attHtml` 是 3 张缩略图(横排 grid)
- 新逻辑:有 attachments 时:
  - 取第一张作为左侧 80x80 缩略图(用 `forumMediaUrl(a.id) + audioTokenQs()` + onerror)
  - 其余 2 张进右侧内容区
  - 整个卡片用 `forum-card has-attachment` 类,CSS 切到 grid 80px 1fr 布局
- 无 attachments 时,布局不变(纯文本卡片)

## 兼容性
- 复用现有 `forumMediaUrl()` `audioTokenQs()` `pickForumCategory()` `loadForumFeed()` `forumApi()`
- 不改 API,不改 CSS 变量
- 移动端:类目条 `overflow-x: auto` + `flex-wrap: wrap` 双向兼顾
