# Design: forum-yield-tuning

## 改动 1:WorldSettings 默认 cap
```java
// WorldSettings.java line 63
// old: private Integer forumMaxPostsPerAgentPerDay = 2;
// new:
private Integer forumMaxPostsPerAgentPerDay = 3;
```

## 改动 2:ForumGenerationService 类目硬推荐
原 prompt 第 188 行:
> 你近 7 天发过的类目:{stats} —— 今天请优先选你没发过或发得少的类目。

新 prompt(在 system 末尾,user 前):
> **类目推荐顺序**(按近 7 天 inverse frequency 排序,优先选 top-3):
> 1. {top-1 类目}(近 7 天 0 帖)← 强烈推荐
> 2. {top-2 类目}(近 7 天 1 帖)
> ...
> 即使 top-3 不完全契合行动主题,也请从中选;返回 [] 必须是"完全没灵感"才用,默认倾向发帖。

实现:
- 在 `buildActionPrompt` 加方法 `pickRecommendedCategories(recent7d, all)`,返回 6 个类目按 inverse frequency 排序(从未发的排最前)
- prompt 末尾追加"类目推荐顺序"段

## 改动 3:AutonomousRunner 多 agent
原 tick:
```java
AgentEmployee actor = active.get(Math.floorMod(cursor.getAndIncrement(), active.size()));
act(actor, active, s.getModel(), s);
```

新 tick:
```java
int perTick = 2;
for (int i = 0; i < perTick; i++) {
    AgentEmployee actor = active.get(Math.floorMod(cursor.getAndIncrement(), active.size()));
    act(actor, active, s.getModel(), s);
}
```

## 风险
- 每 tick 2 agent → LLM 调用 ×2,GLM 限流可能命中。GLM 免费档 60 RPM,30s 2 次 = 4 RPM,远低于限流
