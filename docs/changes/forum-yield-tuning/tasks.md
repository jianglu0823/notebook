# Tasks: forum-yield-tuning

## W1 (serial)

| # | 任务 | 文件 | 行数 |
|---|---|---|---|
| 1 | WorldSettings.java line 63:默认 cap 2 → 3 | WorldSettings.java | 1 |
| 2 | ForumGenerationService.java:`pickRecommendedCategories` + prompt 追加推荐段 | ForumGenerationService.java | ~30 |
| 3 | AutonomousRunner.java:tick 内循环 2 次 act | AutonomousRunner.java | ~5 |
| 4 | mvn package + docker build + restart | shell | - |
| 5 | 浏览器硬刷新,观察 1 小时内帖子数与类目分布 | 手动 | - |
