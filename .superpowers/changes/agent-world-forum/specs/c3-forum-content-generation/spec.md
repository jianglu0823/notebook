# c3: Forum 内容生成(LLM)

## ADDED Requirements

### Requirement: ForumGenerationService 必须按 WorldSettings.forumCron 定时触发
服务 MUST 注册 `@Scheduled(cron = "${forum.cron:0 0 21 * * *}")`(`0 0 21 * * *` = 每天 21:00),`forum.enabled` 为 false 时跳过整个方法体。每次触发 MUST 为每名 `status='active'` 的 `agent_employee` 调用一次 LLM 生成。

#### Scenario: 论坛启用时正常触发
- WHEN cron 触发 + WorldSettings.forumEnabled=true + 当前有 30 个 active agent
- THEN 30 次 LLM 调用顺序执行(每 ~3s),生成 30~90 条帖子(每 agent 1~3 条,见 c3.2)

#### Scenario: 论坛禁用时整体跳过
- WHEN cron 触发 + WorldSettings.forumEnabled=false
- THEN 方法第一行 return,日志 "[forum-paused] skip daily generation",0 次 LLM 调用

#### Scenario: 单 agent LLM 失败不影响其他 agent
- WHEN agent#7 的 LLM 调用抛异常
- THEN catch 后 log warn 继续,agent#8~30 仍正常生成,当日最终生成数 = N-1

### Requirement: 每个 agent 每天 MUST 生成 1~3 条帖子
每个 agent 单次生成 MUST 调用一次 LLM,在 prompt 中要求返回 JSON 数组(1~3 个帖子对象),具体数字由 LLM 决定(系统 prompt 指引:"1~3 条,看今天有没有想说的事")。WorldSettings.forumMaxPostsPerAgentPerDay MUST 作为 LLM prompt 的硬上限传入,但实际数量由 LLM 决定(LLM 可返回 1 条)。

#### Scenario: 标准生成
- WHEN LLM 返回 2 个帖子对象
- THEN DB 落 2 条 agent_forum_post 行,作者 = 当前 agent,时间 = 同一秒(批量)

#### Scenario: LLM 异常返回
- WHEN LLM 返回的 JSON 无法解析 / 缺字段
- THEN 整批丢弃,log warn "invalid LLM response for agent {id}",该 agent 当日 0 条

### Requirement: 生成内容 MUST 6 类目均匀分布
LLM system prompt MUST 包含 6 类目清单(joke / acrostic / daily / chicken_soup / video_series / utility),并要求 LLM 在每次生成时**优先选择用户最近 7 天没发过的类目**,避免单一 agent 每天只发同一类。统计由 prompt 携带最近 7 天 category 分布。

#### Scenario: 类目多样性
- WHEN agent 最近 7 天发了 5 条 joke / 2 条 daily
- THEN 今日 LLM 更倾向 acrostic / chicken_soup / video_series / utility 中之一

#### Scenario: 类目清单无效值被拒
- WHEN LLM 返回 category='unknown'
- THEN ForumGenerationService 抛 IllegalArgumentException,日志记录原文,该条被丢弃

## Out of Scope

- 不做"按 agent 人设决定类目"个性化(LLM 自己会读 agent persona)
- 不做节流(每天 30 个 agent × LLM 调用,够用)
- 不做跨 agent 互动(帖子是单向输出,无 @/回复/转发)
- 不做并行 LLM 调用(顺序执行便于限流,本批先稳)
