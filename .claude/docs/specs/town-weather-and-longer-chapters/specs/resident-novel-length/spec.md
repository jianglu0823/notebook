# Spec: resident-novel-length 居民小说章节加长

本能力将居民 `novel` 技能产出的小说章节,从"80~140 字精彩梗概"升级为"800~1500 字完整章节正文",并通过显式 `max_tokens` 上限保证正文既不被截断、也不会极端跑飞。

## MODIFIED Requirements

### Requirement: 小说章节产出完整正文

居民 `novel` 技能触发创作时,系统 SHALL 生成一段 **800~1500 字的完整小说章节正文**(而非梗概),连同章节标题一并产出。产物结构(标题 / 正文 / 质量分)MUST 与现有 `AgentProduct` 保持一致,不破坏既有存储与展示。

#### Scenario: novel 创作产出完整正文
- **WHEN** 某居民的 novel 技能触发一次创作
- **THEN** 传给模型的 prompt SHALL 要求写"800~1500 字的完整章节正文"(含情节、场景、对白等),而非梗概
- **AND** 产出的 `AgentProduct` SHALL 为 `kind=chapter`,包含标题(`title`)、正文(`content`)与质量分(`quality` 1~10)

#### Scenario: 仅影响 novel,不影响其他产物
- **WHEN** 触发的是 music/image/video 技能创作
- **THEN** 其产物的长度与生成逻辑 SHALL 保持变更前的行为不变

### Requirement: novel 创作设置 max_tokens 上限

novel 章节创作的模型调用 SHALL 显式设置一个 `max_tokens` 上限(约 3000),以保证 800~1500 字正文不被默认上限截断,同时防止极端情况下输出无限增长。该上限 MUST 仅作用于 novel 章节创作,不影响其他 LLM 调用。

#### Scenario: novel 创作请求带 max_tokens
- **WHEN** 发起一次 novel 章节创作的模型调用
- **THEN** 该调用 SHALL 携带显式的 `max_tokens`(约 3000)

#### Scenario: 非 novel 调用不受影响
- **WHEN** 发起日报叙事、居民互评、歌词/画作/短片等非 novel 章节的模型调用
- **THEN** 这些调用 SHALL NOT 因本变更而被强加 novel 的 max_tokens 上限

#### Scenario: 正常长度不被截断
- **WHEN** novel 创作在 max_tokens 约束下生成正文
- **THEN** 800~1500 字的正文 SHALL 能完整产出而不被中途截断(max_tokens 需高于该字数对应的 token 估算)
