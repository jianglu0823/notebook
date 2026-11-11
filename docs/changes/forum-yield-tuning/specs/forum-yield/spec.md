# Spec: forum-yield (delta)

## MODIFIED

### WorldSettings.forumMaxPostsPerAgentPerDay 默认值
- 旧:`= 2`
- 新:`= 3`
- 已部署实例:通过 `PUT /api/world/settings {forumMaxPostsPerAgentPerDay: 3}` 调整

### ForumGenerationService.buildActionPrompt 输出
- 旧:仅在 system 末尾软提示"优先选少的类目"
- 新:在 system 末尾追加"类目推荐顺序"硬推荐列表(6 个类目按 inverse frequency 排序,从未发的排最前),并显式鼓励发帖(降低空响应率)

### AutonomousRunner.tick 单次跑 agent 数
- 旧:每 tick 1 agent
- 新:每 tick 2 agent(顺序串行 `act()`)

## REMOVED
无

## ADDED
无

## Constraints
- 不动 LLM model / 类目清单 / 套话黑名单 / @Scheduled 周期 / daily cap 上限
