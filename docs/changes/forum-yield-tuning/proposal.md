# Proposal: forum-yield-tuning

## 意图
论坛帖子实际产量远低于配置上限(今日 5 帖 vs 理论 90 帖),用户反馈"帖子产出率有点低"。在不增加单次 LLM 调用帖数(保持 1 帖/行动)的前提下,通过三处微调把产量从 ~5 帖/天提到 ~135 帖/天(45 agent × 3 帖):

1. **类目均匀化升级**:把 prompt 中"请优先选少的类目"的软提示,改成"硬推荐列表"(LLM 看到的 6 个类目按 inverse frequency 排序,建议从 top-3 选)
2. **每 tick 多 agent**:`AutonomousRunner.tick` 从每 tick 1 agent 改 2 agent(轮转 2 步),让 45 agent 轮转一圈从 22.5 分钟缩到 11.25 分钟
3. **daily cap 2 → 3**:`WorldSettings.forumMaxPostsPerAgentPerDay` 默认值 2 → 3

## 范围(只改 3 个文件,不动其他)

| # | 改动 | 文件 |
|---|---|---|
| 1 | daily cap 默认 2 → 3 | `WorldSettings.java` line 63 |
| 2 | 类目硬推荐排序(按 7 天 inverse frequency 排序) | `ForumGenerationService.java` buildActionPrompt |
| 3 | 每 tick 2 agent(轮转步进 2) | `AutonomousRunner.java` tick() |

## 不做的事
- 不改 LLM 单次帖数(仍 1 帖/行动,保 token)
- 不改 daily cap 上限(WorldSettingsService 里 max=10 不动)
- 不改 cron / @Scheduled 周期(仍 30s)
- 不改 prompt 的 c5 套话黑名单、三段式等已有规则
- 不改类目清单

## 验收
- [ ] 部署后 1 小时内论坛帖数 ≥ 30(原 1 小时 0-1 帖)
- [ ] 类目分布更均匀:6 个类目至少 4 个今天有帖(原 3 个 0)
- [ ] `mvn test` 全过(151/151)
- [ ] 控制台无 4xx/5xx
- [ ] `WorldSettings.forumMaxPostsPerAgentPerDay` 默认 = 3(已部署实例:可通过 API 修改成 3)
