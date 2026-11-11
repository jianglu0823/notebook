# c1: 暂停现有创作调度

## ADDED Requirements

### Requirement: WorldMediaRunner 必须由 WorldSettings.mediaEnabled 控制开关
`WorldMediaRunner.generateVideo(productId, agentId, date, seq, prompt)` MUST 在调用入口检查 `WorldSettings.mediaEnabled` 标志;为 false 时直接 return,不调用 ZhipuMediaClient,不写库,只 log info 一行。代码本体不动(原 submit→poll→download→回填逻辑完整保留)。

#### Scenario: mediaEnabled=false 时不触发
- WHEN WorldSettings.mediaEnabled=false 且 WorldSimEngine 调用 generateVideo
- THEN 方法在第一行 return,日志包含 "[media-paused] skip video productId={}",ZhipuMediaClient 不被调用,agent_product 表无新行

#### Scenario: mediaEnabled=true 时原有行为不变
- WHEN WorldSettings.mediaEnabled=true
- THEN generateVideo 走原 submit→poll→download→回填全流程,行为与暂停前一致

### Requirement: WorldSettings.mediaEnabled 默认值必须为 false
数据库 seed/init 脚本 MUST 把 WorldSettings.mediaEnabled 字段默认值设为 false;WorldSettingsService.getOrCreate() 在记录不存在时插入的默认值 MUST 是 false。

#### Scenario: 数据库为空时首次启动
- WHEN 应用启动且 WorldSettings 表无记录
- THEN WorldSettingsService.getOrCreate() 返回 settings.mediaEnabled=false

#### Scenario: 手动置为 true 后可恢复
- WHEN 用户通过 settings API PUT /api/world/settings 把 mediaEnabled 改为 true
- THEN WorldMediaRunner 立即恢复原行为(下次调用即生效,无 cache)

### Requirement: WorldMediaRunner 必须保留原代码并标注 deprecated
`WorldMediaRunner` 类 Javadoc MUST 包含 `@Deprecated(since="2026-08-12")` 注释,说明"原短片生成逻辑暂停,代码保留等待论坛 v2 评估"。原 submit/polling/download/回填逻辑 MUST 不被删除。

#### Scenario: 暂停后代码可读
- WHEN 开发者查看 WorldMediaRunner.java
- THEN 类 Javadoc 包含 "paused 2026-08-12" 字样,原 4 个常量(MAX_POLLS/POLL_INTERVAL_MS 等)与 @Async generateVideo 方法体完整存在

## Out of Scope

- 不删除 ZhipuMediaClient,后续论坛图/视频仍复用
- 不改 AgentProductRepository / AgentProduct 实体
- 不改 WorldDailyReport / AgentAction
