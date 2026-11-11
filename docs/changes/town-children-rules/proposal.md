# Proposal: town-children-rules

## Why

`AgentEmployeeService.birth()` 与 `WorldSimEngine.dailySettlement().childbirths()` 给孩子硬编码 `👶` 一个头像;`childbirths()` 每日 8% 概率让每对育龄已婚夫妇怀上,30 天分娩,无任何节流。后果:

1. **视觉单一**:小镇地图 / 论坛头像 / 视频配图里所有孩子长得一样,失去个性。
2. **人口爆炸**:跑得越久,孩子越多;没有每对夫妇上限也没有产后冷却,一两年下来孩子能比成人还多,严重失衡。

## What Changes

3 个独立子改动 + 1 个数据迁移:

1. **孩子头像池**:`AgentEmployeeService.birth()` 不再硬编码 `👶`,从 9 个儿童 emoji 中随机抽一个传入。
2. **出生节流**:`WorldSimEngine.childbirths()` 在"8% 怀上"前置一道闸门——按 `parent_ids` 反查该夫妇已有孩子数,达到上限 (`MAX_CHILDREN_PER_COUPLE = 3`) 则跳过当日怀上判定。
3. **历史数据迁移**:应用启动时一次性跑 `MigrateChildDataRunner`,把所有 `parentIds IS NOT NULL` 的居民的 `avatar` 字段按新池重抽,并对已超 3 子的夫妇清掉 `pregnantSince`/`dueDate`(防止历史累积的孕期再生)。
4. **配置常量**:把头像池和上限都抽到 `application.yml` 的 `world.children.*` 段,允许不改代码调整。

## Scope

### In

- `AgentEmployeeService.birth()`:avatar 参数用池内随机值代替硬编码 `👶`。
- `WorldSimEngine.childbirths()`:在每日怀上判定前查该夫妇现有孩子数,过限则跳过。
- 新增 `MigrateChildDataRunner`:`CommandLineRunner`,启动时迁移历史孩子的 avatar + 清超额孕期字段;幂等。
- `application.yml`:新增 `world.children.avatar-pool` 和 `world.children.max-per-couple`。
- `WorldSimEngineTest`(如有):补测试覆盖怀上节流(过限夫妇不再怀上)。
- `MigrateChildDataRunnerTest`:补测试覆盖空表 / 混合 / 已正常孩子。

### Out

- 不引入 `sex` / `gender` 字段(避免 schema 迁移,改用纯池随机)。
- 不做 `mom` 孕期 UI / 推送 / 倒计时相关改动(独立 change)。
- 不调头像池之外的视觉(色卡 / moodEmoji / visualDescriptor)——只换 `avatar` 字段。
- 不动离婚 / 死亡相关逻辑(只动出生)。
- 不改 `uniqueChildName()` 重名检测(已有,继续用)。

## Impact

- **数据**:启动时一次性 UPDATE `agent_employee.avatar` (条件 `parent_ids IS NOT NULL`);若有过额孕期则 UPDATE 清 `pregnant_since` / `due_date`。事务内完成,可回滚。
- **运行时**:节流后,过限夫妇不再进入怀上判定,单次 `dailySettlement()` 少几条循环,无性能影响。
- **API / 前端**:无新端点,前端无需改动(读 `avatar` 字段即可,值不同而已)。
- **测试**:已有 `WorldSimEngine` 测需更新断言;新增迁移 runner 测试。
- **配置**:`application.yml` 加 2 个 key,旧部署无此 key 时使用默认值(头像池硬编码 9 个,上限 3)。

## Capabilities

- `agent.birth.avatar-diversity` — 孩子头像从池中随机抽,打破 `👶` 单一。
- `agent.birth.throttle` — 每对夫妇最多 N 个孩子(N 默认 3),达到后不再怀上。
- `agent.birth.migrate-historical` — 启动时把历史孩子的 avatar 改写,清理已超额夫妇的孕期字段。

## Acceptance

- 新出生的孩子 `avatar` 字段值来自 9 个 emoji 池,不是固定 `👶`。
- 同一对夫妇已有 3 个孩子后,后续 `dailySettlement` 不再让这对夫妇怀上。
- 应用启动后,历史孩子(任意 5 个抽查)`avatar` 字段值是池中某个;过额夫妇的 `pregnantSince` / `dueDate` 为 null。
- `MigrateChildDataRunner` 二次启动不改任何数据(幂等)。
- 已有测试 194+ 个继续 pass,新增测试覆盖节流 + 迁移。
