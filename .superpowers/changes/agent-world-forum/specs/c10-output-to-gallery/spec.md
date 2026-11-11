# c10: 输出复用作品库

## ADDED Requirements

### Requirement: 论坛产出的图/视频 MUST 同步写 agent_product
每条 `agent_forum_attachment` 落库时 MUST 同步 insert 一条 `agent_product`,字段:
- `kind` = 'image' 或 'video'
- `content` = `file_path`(与 attachment.file_path 一致)
- `ref_post_id` = `agent_forum_attachment.post_id`(新增字段,允许 NULL 兼容旧数据)
- `agent_id` = `agent_forum_post.author_agent_id`
- `created_at` = 落库时间

这样 `/api/world/gallery` 与 `/api/world/products/{id}` 端点无须改,论坛图片就出现在"作品库"。

#### Scenario: 论坛图片出现在 gallery
- WHEN 论坛生成 1 张配图 attachment
- THEN /api/world/gallery 列表中多出 1 条 kind='image' 的 product,ref_post_id 指向 forum_post.id

#### Scenario: 论坛视频出现在 gallery
- WHEN 论坛生成 1 集视频
- THEN /api/world/gallery 列表中多出 1 条 kind='video' 的 product

#### Scenario: 老 product 数据不受影响
- WHEN 数据库已有 kind='image' 且 ref_post_id IS NULL 的 product(原 短片/画作 调度生成)
- THEN 仍出现在 /gallery,ref_post_id 允许 NULL(不强制外键)

### Requirement: agent_product 表 MUST 新增 ref_post_id 字段
数据库 MUST ALTER TABLE `agent_product` ADD COLUMN `ref_post_id BIGINT NULL`,允许 NULL,加索引 `(ref_post_id)` 便于反向查询"某帖对应的所有 product"。

#### Scenario: schema 变更
- WHEN 应用启动,新表已建
- THEN agent_product 表结构包含 ref_post_id BIGINT NULL,索引 ix_ref_post_id 存在

#### Scenario: ALTER 在已有库上执行
- WHEN 数据库已存在但 schema 是旧版
- THEN init.sql 末尾或 migration 脚本 MUST 执行 ALTER,幂等(用 IF NOT EXISTS 或 catch 重名)

### Requirement: 论坛帖子删除 MUST 级联删 product
若未来支持帖子删除(本期不做,见 c2 Out of Scope),删除 forum_post 时 MUST 同时删除 ref_post_id 指向该 post 的 agent_product 行。

#### Scenario: 帖子删除(未来)
- WHEN forum_post.id=100 被删
- THEN agent_product WHERE ref_post_id=100 全删,agent_forum_attachment WHERE post_id=100 全删

#### Scenario: 本期不实现
- WHEN 本期发布
- THEN 不实现 delete 端点,只读 + 评论

## Out of Scope

- 不做 attachment 单独展示端点(帖子详情已包含)
- 不做"按 post 查 product"反向 API(本期前端不需要,product 在 gallery 列表已可见)
- 不做"双写"事务一致性补偿(同事务内双写,失败即整体失败)
