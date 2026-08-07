# Spec: companion-crud

AI 伴侣元数据管理：创建/查询/更新/删除/列出 用户的 AI 伴侣；提供 12 个预设模板的快速导入。

## ADDED Requirements

### Requirement: 伴侣创建（5 字段快速路径）

系统 SHALL 提供 REST 端点创建 AI 伴侣。快速路径 MUST 至少接受 5 个必填字段：`name`（2-20 字符，文本）、`gender`（枚举：男/女/无性别/自定义）、`personalityTags`（字符串数组，0-5 个）、`age`（18-99 整数）、`bio`（≤200 字符）。任一必填字段缺失或越界 MUST 返回 400。未指定默认模型时 MUST 落 `ChatModelFactory.defaultTextModel()`（`glm-4.7-flash`）。

#### Scenario: 完整 5 字段创建成功
- WHEN 用户 POST `/api/companion` 携带 `{name:"小灵", gender:"女", personalityTags:["温柔体贴","知性优雅"], age:25, bio:"咖啡店常客"}`
- THEN 返回 201，响应体含 `id`、默认 `defaultModel="glm-4.7-flash"`、`intimacyScore=0`、`createdAt`
- AND 数据库 `companion` 表新增一行，`status='active'`

#### Scenario: name 越界返回 400
- WHEN 用户 POST 携带 `name` 长度 21 的字符串
- THEN 返回 400，错误消息提示"name 长度需 2-20"

#### Scenario: 必填字段缺失返回 400
- WHEN 用户 POST 缺 `gender`
- THEN 返回 400，错误消息提示"gender 必填"

### Requirement: 伴侣创建（10 字段完整定制）

创建或更新伴侣时，系统 SHALL 接受 5 字段之外的 5 个可选字段：`voiceStyle`（枚举：温柔甜美/低沉磁性/清亮少年/成熟御姐/奶萌可爱/沙哑质感/知性平静，默认"温柔甜美"）、`relationshipType`（枚举：恋人/挚友/知己/导师/管家/自定义，默认"知己"）、`intimacyStart`（1-100 整数，默认 30）、`speakingStyle`（枚举：正式书面/日常口语/网络用语/古风文言/学术严谨/撒娇卖萌/简洁干练，默认"日常口语"）、`backgroundStory`（≤500 字符）。任一字段越界 MUST 返回 400。

#### Scenario: 完整 10 字段创建
- WHEN 用户 POST 携带全部 10 字段
- THEN 201 响应，所有 10 字段回显在响应体
- AND `intimacyScore=intimacyStart`

#### Scenario: 部分可选字段缺失走默认值
- WHEN 用户 POST 仅 5 字段
- THEN `voiceStyle=温柔甜美`、`relationshipType=知己`、`intimacyStart=30`、`speakingStyle=日常口语`、`backgroundStory=""`

### Requirement: 12 预设模板

系统 SHALL 在 `companion_template` 表预置 12 个模板（温柔学姐/霸道总裁/元气少女/清冷剑仙/毒舌损友/优雅贵妇/全能管家/猫耳女仆/学术导师/摇滚歌手/魔法师/职场前辈），每个模板 MUST 包含 10 字段完整配置 + 推荐 `defaultModel`。GET `/api/companion/templates` MUST 返回全部 12 个模板。

#### Scenario: 列出全部模板
- WHEN 用户 GET `/api/companion/templates`
- THEN 返回 200，数组长度 = 12，每项含 `templateKey/name/gender/personalityTags/voiceStyle/relationshipType/speakingStyle/backgroundStory/defaultModel`

#### Scenario: 从模板创建伴侣
- WHEN 用户 POST `/api/companion/from-template/{templateKey}`（如 `gentle_senior`）
- THEN 201 返回新伴侣，所有字段从模板复制，`createdAt=now`

### Requirement: 伴侣列表 + 详情

GET `/api/companion` MUST 返回当前用户全部 `status='active'` 伴侣（按 `lastMessageAt DESC, createdAt DESC`）。GET `/api/companion/{id}` MUST 返回单个伴侣详情。任一不存在的 `id` MUST 返回 404。

#### Scenario: 列出全部伴侣
- WHEN 用户已有 3 个伴侣
- THEN GET `/api/companion` 返回长度 3 的数组

#### Scenario: 详情查询
- WHEN 用户 GET `/api/companion/123`
- THEN 返回 200，响应体含 10 字段 + `intimacyScore` + `lastMessageAt`

### Requirement: 伴侣更新与删除

PATCH `/api/companion/{id}` SHALL 支持部分更新（任意 10 字段中部分）；DELETE `/api/companion/{id}` SHALL 软删除（设 `status='deleted'`，不物理删除），同时 MUST 标记该伴侣下的 `companion_message` 行 `hidden=true`，并从 Milvus `companion_memories` 删该 `companion_id` 的所有向量。

#### Scenario: 部分字段更新
- WHEN 用户 PATCH 携带 `{bio:"新签名"}`
- THEN 仅 `bio` 字段更新，`updatedAt=now`

#### Scenario: 删除软标记
- WHEN 用户 DELETE `/api/companion/123`
- THEN 返回 204；`companion.status='deleted'`；该伴侣后续 GET 列表不返回；GET 详情返回 404

### Requirement: 所有权隔离

任一伴侣的 CRUD 操作 MUST 仅允许其所属用户操作；其他用户的伴侣 ID MUST 返回 404（不暴露存在性）。

#### Scenario: 跨用户访问被拒
- WHEN 用户 A 的请求携带用户 B 的伴侣 ID
- THEN 返回 404
