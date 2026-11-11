# Execution Contract: forum-yield-tuning

## Workflow
- **Mode**: tweak
- **Plan revision**: 1 (sdd, single wave W1 serial)

## Scope fence
仅修改 3 个文件:
- `WorldSettings.java`(1 行)
- `ForumGenerationService.java`(~30 行)
- `AutonomousRunner.java`(~5 行)

## Out of scope
LLM model / 类目 / 套话 / @Scheduled / 前端 / DB schema

## Acceptance
1. `mvn test` 通过(151/151)
2. 部署后 1 小时内论坛新增帖数 ≥ 20
3. 类目分布更均匀:6 个类目至少 4 个今天有帖
4. 控制台无 5xx,无 LLM 限流报错

## Rollback
`git revert` + `docker compose build app && docker rm -f nblm-app && docker compose up -d app`
+ `PUT /api/world/settings {forumMaxPostsPerAgentPerDay: 2}` 还原 cap

## Owner
蒋路
