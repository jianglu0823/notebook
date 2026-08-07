# Spec: C9 主题 Emoji 匹配

为待办事项匹配主题 Emoji，让清单生动化，对抗"清单疲劳"。

## ADDED Requirements

### Requirement: Emoji 字典种子

`schedule_emoji_dict` 表 MUST 预置 ≥ 30 条种子数据，覆盖常用场景：会议/工作/学习/运动/餐饮/医疗/家庭/休闲/出行/财务/社交/纪念日。每条含 `keyword` (数组) / `emoji` (单个字符) / `category` (枚举) / `weight` (整数, 默认 1)。种子在应用启动时通过 `data-emoji-dict.sql` 加载，重复执行幂等。

#### Scenario: 启动种子加载
- WHEN 应用首次启动
- THEN `schedule_emoji_dict` 写入 30+ 条；`SELECT COUNT(*) FROM schedule_emoji_dict` ≥ 30

#### Scenario: 重复启动幂等
- WHEN 第二次启动
- THEN `ON DUPLICATE KEY UPDATE` 不报错，行数不变

#### Scenario: 字典条目示例
- WHEN 查询 `category="运动"`
- THEN 至少含 🏃（跑步）/ 🏋️（健身）/ 🚴（骑行）/ ⚽（球类）/ 🧘（瑜伽）等

### Requirement: Emoji 解析 API

`POST /api/schedule/v1/emoji/resolve` MUST 接受 `{title, description?}` body，返回 `{emoji, matchedKeyword, score}`。`emoji` 字段是单个字符或 fallback `📌`。`score` 范围 0~1，≥ 0.5 视为命中。匹配算法：title 文本分词后与 `keyword` 数组做 Jaccard 相似度，取 `score * weight` 最高者。

#### Scenario: 命中关键词
- WHEN POST `{title:"团队周会", description:"讨论Q3 OKR"}`
- THEN 返回 `{emoji:"📅", matchedKeyword:"会议", score:0.7}`

#### Scenario: 部分命中
- WHEN POST `{title:"和朋友吃饭"}`
- THEN 返回 `{emoji:"🍽", matchedKeyword:"聚餐", score:0.5}`

#### Scenario: 未命中
- WHEN POST `{title:"随便写点东西"}`
- THEN 返回 `{emoji:"📌", matchedKeyword:null, score:0.0}`

#### Scenario: 多个候选取最高
- WHEN title 同时命中"运动"和"户外"
- THEN 返回 `weight` 乘积较高者

### Requirement: 创建事件自动分配 Emoji

`POST /api/schedule/v1/events` 时，若请求 body 未指定 `emoji` 字段，MUST 自动调 `EmojiService.resolve(title, description)` 解析，解析结果落 `schedule_event.emoji` 列。响应体 MUST 返回最终 emoji。

#### Scenario: 自动分配
- WHEN POST `{title:"团队周会"}` 不带 emoji
- THEN 服务端自动解析为 📅；响应体 `emoji="📅"`；DB `schedule_event.emoji="📅"`

#### Scenario: 显式覆盖
- WHEN POST `{title:"团队周会", emoji:"⭐"}`
- THEN 不走自动解析，DB `emoji="⭐"`

### Requirement: 字典可扩展

`POST /api/schedule/v1/emoji/dict` MUST 接受新条目 `{keywords:[...], emoji, category, weight}`，落 DB 同时刷新内存缓存（`Caffeine` 5min TTL）。`GET` MUST 列出当前生效的全部条目。

#### Scenario: 动态新增
- WHEN POST 新条目 `{keywords:["画画"], emoji:"🎨", category:"休闲"}`
- THEN 立即可被 resolve API 命中；`GET /emoji/dict` 列表新增一行

#### Scenario: 缓存刷新
- WHEN DB 新增条目
- THEN 内存 Caffeine 缓存 5min 内自动过期刷新

### Requirement: 性能

`POST /emoji/resolve` P95 响应延迟 MUST < 50ms（含 Caffeine 缓存命中）。缓存未命中（首次调）MUST < 200ms。

#### Scenario: 缓存命中
- WHEN 第二次解析相同 title
- THEN 延迟 < 50ms；日志 `emoji cache hit`

#### Scenario: 冷启动
- WHEN 应用启动后首次调 resolve
- THEN 延迟 < 200ms
