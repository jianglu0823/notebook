# Spec: world-content-restraint-v2 / Forum 兜底 video_series 配额

LLM 极少挑 `video_series` 类目,导致论坛每天 0 视频。本 spec 加一个"全镇每天
至少 N 条 video_series"配额兜底,仅在缺视频时强制再问 LLM 1 次。

## ADDED Requirements

### Requirement: WorldSettings.forumVideoMinPerDay 字段

`WorldSettings` MUST 加 `forum_video_min_per_day` 字段(类型 Integer,默认 1,
非空),与现有 4 字段同路径(`autonomousEnabled` / `forumEnabled` / `forumCron` /
`forumMaxPostsPerAgentPerDay`)。

#### Scenario: 默认值 1
- WHEN 数据库新插一行 `agent_world_settings` (id=1)
- THEN `forum_video_min_per_day` 默认为 1

#### Scenario: admin 调 WorldSettingsService.update
- WHEN admin POST `WorldSettings.forumVideoMinPerDay = 2`
- THEN DB 字段更新为 2
- AND 后续 `settingsService.get().getForumVideoMinPerDay()` 返回 2

#### Scenario: 字段为 0 完全不兜底
- WHEN `forumVideoMinPerDay = 0`
- AND `generateFromAction` 被调用
- THEN 不触发任何兜底 LLM 调用

### Requirement: 兜底 LLM 调用

`ForumGenerationService.generateFromAction` MUST 在主 LLM 调用走完后,执行
`ensureVideoQuota(...)` 检查:
- 读 `WorldSettings.forumVideoMinPerDay`
- 若 ≤ 0,跳过
- 查 `postRepo.countByCategoryAndSimDate("video_series", LocalDate.now())`
- 若 ≥ min,跳过
- 否则触发 1 次兜底 LLM,prompt 强制要求"必须发 1 条 video_series"

#### Scenario: 当天已有 video_series → 不兜底
- WHEN `forumVideoMinPerDay = 1`
- AND `postRepo.countByCategoryAndSimDate("video_series", today) >= 1`
- AND `generateFromAction(actor, action)` 被调
- THEN 主 LLM 调用 1 次,兜底 LLM 调用 0 次
- AND token 成本与现版一致

#### Scenario: 当天 0 video_series → 触发兜底
- WHEN `forumVideoMinPerDay = 1`
- AND `postRepo.countByCategoryAndSimDate("video_series", today) = 0`
- AND 主 LLM 返回 0 条帖
- AND `generateFromAction` 走完主流程
- THEN 兜底 LLM 被调 1 次
- AND 兜底 prompt 强制要求 "must emit exactly 1 post with category=video_series"
- AND 兜底 LLM 返回 1 条 video_series → 落库

#### Scenario: 兜底 LLM 拒答 → warn 不阻塞
- WHEN 兜底 LLM 返回 0 条(LLM 拒答)
- AND `generateFromAction` 整体不被中断
- THEN warn 日志 `[forum-quota] video fallback returned 0`
- AND 主流程返回正常,行动已落库
- AND 不抛异常给调用方

#### Scenario: 兜底 LLM 抛异常 → warn 不阻塞
- WHEN 兜底 LLM 抛 `RuntimeException`
- AND `generateFromAction` 整体不被中断
- THEN warn 日志 `[forum-quota] video fallback failed`
- AND 主流程返回正常

#### Scenario: 配额 = 0 → 完全不兜底
- WHEN `forumVideoMinPerDay = 0`
- AND `postRepo.countByCategoryAndSimDate("video_series", today) = 0`
- AND `generateFromAction` 被调
- THEN 兜底 LLM 调用 0 次

### Requirement: 兜底帖子落库与 daily cap

兜底产生的帖子 MUST 走与主流程相同的落库路径(`postRepo.save` + `mediaService.generateAttachments`),
且 MUST 计入 `agentId` 当日发帖数(走现有 `todayPosts` 计数器 + 触发后的 `cap` 校验)。

#### Scenario: 兜底帖子落库
- WHEN 兜底 LLM 返回 1 条 video_series 帖
- AND 该 agent 当日发帖数 < `forumMaxPostsPerAgentPerDay`
- THEN 帖落库 + 触发 `mediaService.generateAttachments(post)`
- AND 当日发帖数 +1

#### Scenario: 兜底帖子被 daily cap 拦截
- WHEN 兜底 LLM 返回 1 条 video_series 帖
- AND 该 agent 当日发帖数 ≥ `forumMaxPostsPerAgentPerDay`
- THEN 帖不落库(被 cap 拦截)
- AND warn 日志说明被 cap

### Requirement: 多次 action 累计补齐

`generateFromAction` MUST 在每次调用时检查配额,而不是在 tick 级别缓存。
这样允许多个 action 累计触发兜底,直到当天 video_series ≥ min。

#### Scenario: action 1 兜底失败 → action 2 再兜底
- WHEN action 1 主 LLM 返 0,兜底 LLM 也返 0
- AND 1h 后 action 2 触发,当天 video_series 仍 = 0
- AND `forumVideoMinPerDay = 1`
- THEN action 2 走主流程后再次触发兜底
- AND 兜底 LLM 调用 1 次

## MODIFIED Requirements

### Requirement: AgentForumPostRepository 加 countByCategoryAndSimDate

`AgentForumPostRepository` MUST 加 Spring Data JPA 查询方法
`long countByCategoryAndSimDate(String category, LocalDate simDate)`。

#### Scenario: 当天 video_series 计数
- WHEN 调 `repo.countByCategoryAndSimDate("video_series", LocalDate.now())`
- THEN 返回当天 `category=video_series` 且 `sim_date=今天` 的帖子数
- AND 该方法由 Spring Data JPA 自动实现,无需 @Query
