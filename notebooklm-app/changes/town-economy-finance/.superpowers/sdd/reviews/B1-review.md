# Wave B1 Review — 经济实体/仓库 + applyDelta 统一入账

- **Wave**: B1 (Tasks 1.1, 1.2, 1.3)
- **Range**: 34040b2..working-tree(未提交)
- **Verdict**: pass

## 交付物
- 5 实体 + 仓库对:`TownEconomyDaily`/`PlaceRevenue`/`TownStock`/`StockDaily`/`StockHolding` 及各自 Repository。
- `AgentTransactionRepository` 新增聚合查询(净收入/月收入/全镇收支)。
- `TownEconomyService.applyDelta` 统一入账;`TownEconomyServiceTest` 6 用例全绿。

## Spec 合规
- **R2/R4/R9/R10 实体落位**:实体字段与 init.sql/migration.sql 列一致(sim_date/place_key/code/agent_id 等),幂等键有对应 finder(`findBySimDateAndPlaceKey`/`findBySimDateAndCode`/`findByAgentIdAndCode`)。✅
- **C2 全额记账(取消 ≥100 阈值)**:`applyDelta` 对任意非零 delta 都落 `agent_transaction`;测试 `smallAmount_stillRecorded_noThreshold`(delta=5)验证。✅
- **余额永不为负**:负 delta 超余额时封顶至 -before;测试 `expense_cappedSoCoinsNeverNegative`(30 扣 100 → applied=-30, coins=0)。✅
- **净收入/月收入聚合**:`sumDeltaByAgentAndDate`、`sumDeltaByAgentGroupByMonth`([year,month,sum])、全镇 `sumDeltaByDate`/`sumIncomeByDate`/`sumExpenseByDate` 就位,供 B2/B4/B5 消费。✅

## 测试策略说明
- 纯逻辑(applyDelta 不变量)用 Mockito 单测,6 用例覆盖收入/小额/支出/封顶/零值/恰好清零。
- 仓库 `@Query` 聚合(`year()`/`month()` 方言)不建 H2 测试库:生产为 MySQL,H2 方言差异会给假信心,留待部署时按 migration.sql 建表后验证。

## 编译/测试
- `mvn -o clean compile` 通过。
- `mvn test -Dtest=TownEconomyServiceTest` → Tests run: 6, Failures: 0, Errors: 0。

## 发现
- 无 Critical / Important。
- Minor:`applyDelta` 目前不写 `town_economy_daily`(全镇聚合)—按设计留给 B4 结算落盘,B1 只提供入账原语,符合契约分层。

## 结论
B1 实体、聚合查询、统一入账原语齐备且经单测验证;编译通过。B1 通过,解锁 B2/B3。
