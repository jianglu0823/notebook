# B4 Review: 智能排期规则引擎 + 深度思考 LLM 增强

**Wave**: B4-scheduler
**Branch**: schedule-agent-build
**Base**: 0f4d395
**Head**: 3e92706
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B4 要求产出:
- [x] ScoringFunction 4 维评分 (偏好 40 + 距离 20 + 优先级 20 + 历史 20)
- [x] RuleScheduler 时间窗切片 + Top-3 推荐
- [x] 冲突检测 (滑动窗口)
- [x] 用户偏好集成 (UserPrefService)
- [x] DeepScheduler 触发判断 (3 任务含 P0 / 关键字 / 低分)
- [x] DashScopeDeepScheduleClient LLM 调用
- [x] DeepScheduleCacheService 24h Caffeine 缓存
- [x] 主模型失败降级 glm-4-flash
- [x] 10s 超时降级到 C4 规则引擎
- [x] SchedulerController REST 接口

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c4 R 评分 | 4 维评分 100 分 | ScoringFunction.score() |
| c4 R Top-3 | 返回 top 3 候选 | RuleScheduler.recommend() |
| c4 R 冲突 | 时间冲突跳过 | RuleScheduler 检测 |
| c4 R 偏好 | 上午不排会等 | UserPrefService 集成 |
| c4 R 中文 reason | 前 2 维度拼接 | ScoringFunction.reason() |
| c4 R 邻近替代 | conflict_with | RuleScheduler 邻近滑动 |
| c5 R 触发 | 3 任务含 P0 / 关键字 / 低分 | DeepScheduler.shouldTrigger() |
| c5 R LLM | DashScope 集成 | DashScopeDeepScheduleClient |
| c5 R 降级 | 解析失败/超时/网络 | fallback 到 RuleScheduler |
| c5 R 缓存 | 24h 命中 | DeepScheduleCacheService Caffeine |
| c5 R 模型降级 | 主模型失败 → glm-4-flash | DeepScheduleClient chain |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 117, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B4 新增 28 测试:ScoringFunction 6 + RuleScheduler 8 + DeepScheduler 8 + DeepScheduleCache 3 + DeepScheduleClient 3。

## 关键工程决策

1. 评分权重 (40/20/20/20) 写死在 ScoringFunction:spec 锁定。
2. 触发判断 3 条件 (3 任务含 P0 / 关键字 / 低分) 任一满足。
3. 关键字检测:title/description 含 "深度思考"/"帮我规划"。
4. DashScopeDeepScheduleClient Future.cancel(true) on 10s timeout。
5. 缓存 key = userId+title+timeBlocks sha256:避免大 key。
6. 模型降级链:qwen-max → qwen-plus → glm-4-flash,任一成功即返回。
7. 解析失败 catch + WARN log + 走规则引擎,不抛用户可见异常。

## 已知后续

- 评分权重当前硬编码,后续可走 UserPrefService 配置。
- 缓存 24h 不主动失效,LLM 改 prompt 后需重启。
- 模型降级链未配置 fallback budget,长失败链可能耗时。
