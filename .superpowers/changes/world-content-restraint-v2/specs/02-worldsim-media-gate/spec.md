# Spec: world-content-restraint-v2 / WorldSimEngine 守门 media_enabled

修复 `WorldSettings.mediaEnabled=false` 时,小镇仍继续产图/产视频/产小说/产歌
的 regression。`WorldSimEngine` 必须彻底尊重这个 flag,所有 produce 方法在
`mediaEnabled=false` 时不调下游、不写库。

## ADDED Requirements

### Requirement: maybeCreate 守门

`WorldSimEngine.maybeCreate` MUST 在方法顶部检查 `WorldSettings.mediaEnabled`;
当其为 `false` 时,直接 return `null`,不调 LLM 创作 prompt、不调任何 produce 方法。

#### Scenario: mediaEnabled=false 时 maybeCreate 返回 null
- WHEN `WorldSettings.mediaEnabled == false`
- AND 任意 agent 拥有 creative skill
- AND 调 `WorldSimEngine.maybeCreate(agent, date, skills, tok, eff)`
- THEN 返回 `null`
- AND `agent_product` 表无新行

#### Scenario: mediaEnabled=true 时行为保持
- WHEN `WorldSettings.mediaEnabled == true`
- AND 调 `WorldSimEngine.maybeCreate(...)`
- THEN 行为与现状一致(走 LLM 创作,调对应 produce 方法)

### Requirement: produceArtwork 二次短路

`WorldSimEngine.produceArtwork` MUST 在调 `mediaClient.generateImage` 之前检查
`mediaEnabled`;若为 `false`,直接 return `null`,不调 LLM 标题生成、不调 cogview。

#### Scenario: 守门时跳过 CogView
- WHEN `mediaEnabled == false`
- AND 调 `produceArtwork(agent, date, "写意", tok)`
- THEN 返回 `null`
- AND `mediaClient.generateImage` 未被调用
- AND `agent_product` 表无新行

### Requirement: produceVideo 二次短路 + 不写占位

`WorldSimEngine.produceVideo` MUST 在调 `mediaRunner.generateVideo` 之前检查
`mediaEnabled`;若为 `false`,直接 return `null`,**且不写占位 `AgentProduct` 行**
(content 为空会让前端显示"生成失败"卡片)。

#### Scenario: 守门时跳过 CogVideoX 与占位行
- WHEN `mediaEnabled == false`
- AND 调 `produceVideo(agent, date, "电影", tok)`
- THEN 返回 `null`
- AND `mediaRunner.generateVideo` 未被调用
- AND `agent_product` 表无新行(含占位行)

### Requirement: produce (novel/song) 二次短路

`WorldSimEngine.produce` MUST 在调 LLM 创作之前检查 `mediaEnabled`;若为
`false`,直接 return `null`,不调 LLM、不写 `AgentProduct`。

#### Scenario: 守门时跳过 novel 创作
- WHEN `mediaEnabled == false`
- AND 调 `produce(agent, date, "chapter", "novel", "古风", tok)`
- THEN 返回 `null`
- AND LLM chat 未被调用
- AND `agent_product` 表无新行

#### Scenario: 守门时跳过 song 创作
- WHEN `mediaEnabled == false`
- AND 调 `produce(agent, date, "song", "song", "民谣", tok)`
- THEN 返回 `null`
- AND LLM chat 未被调用
- AND `agent_product` 表无新行

### Requirement: dailySettlement 端到端零产物

`WorldSimEngine.dailySettlement` MUST 在 `mediaEnabled == false` 时,跑完整流程
不产生任何 `agent_product` 新行。

#### Scenario: 7 active 居民全 0 产物
- WHEN `mediaEnabled == false`
- AND `dailySettlement(date)` 跑一次,7 active 居民均拥有 creative skill
- THEN `agent_product` 新增行数 = 0
- AND 返回的 `stats` 中 `artworks=0, films=0, chapters=0, songs=0`
- AND Phase A/B/D 流程正常执行(工资/自学/互评不受影响)

## MODIFIED Requirements

### Requirement: WorldSimEngine 注入 WorldSettingsService

`WorldSimEngine` MUST 注入 `WorldSettingsService` 依赖(走 Lombok
`@RequiredArgsConstructor` 自动连线),用于读 `WorldSettings.mediaEnabled`。

#### Scenario: Spring 启动注入
- WHEN Spring 启动构造 `WorldSimEngine` bean
- THEN `WorldSettingsService` 通过构造器注入(无 `@Autowired` 字段)
