# Execution Contract: 智能体小镇 经济与金融体系

> 单一执行握手。以本契约为准绳实现;偏离范围/需求须回退规划(spec-writer)并刷新本契约。

## Intent Lock

**问题**:小镇经济只有"发钱"——每人每日固定随机工资,金币只增不减、不循环;无消费、无商业营收、无资金池、无月度汇总、无市场,前端也几乎不展示经济数据。
**范围**:把小镇升级为会循环运转的经济体(居民挣钱+花钱、场所营收、作品售卖、资金池与经济健康统计、日/月净收入),并引入居民自主炒股的股票市场,前端可视化展示。四能力:`town-economy-core`、`resident-income`、`town-stock-market`、`town-economy-ui`。

## Scope Fence (Out of Scope — 越界即回退规划)

- 不做真实货币/支付,纯虚拟金币(整数)。
- 玩家本期**仅观看**股市,不亲自交易(玩家操盘留后续变更)。
- 无实时/秒级行情;股价按"每日结算"节奏更新。
- 不重写自主行动/漫游引擎;经济逻辑只挂在 `dailySettlement` 内。
- 不改动创作/关系/死亡等既有玩法本身(仅让作品产出接入售卖收入)。

## Constraints (来自 design.md Decisions)

- **C1**:经济/股市逻辑拆为独立 `TownEconomyService` + `StockMarketService`,由 `WorldSimEngine.dailySettlement` 按序调用;不膨胀 WorldSimEngine。(D1)
- **C2**:所有金币变动统一走 `TownEconomyService.applyDelta(e,date,reason,delta)`,全额写 `agent_transaction`(取消既有 ≥100 门槛);负 delta 封顶到 0(不透支)。(D2/D5)
- **C3**:日/月净收入用聚合查询(SUM delta),**不建冗余汇总表**。(D3)
- **C4**:消费/售卖/股市**全程零 LLM**,规则化;关键系数集中可配(`CONSUME_BASE/RANGE`、`SELL_K/BASE`、`STOCK_MAX_PCT` 默认 0.20)。(D5/D6/D7)
- **C5**:消费类别→场所固定映射(餐饮→food/日用→grocery/医疗→clinic/娱乐→market);落地前 `grep` 核对 `TownMap` 真实 key。(D4)
- **C6**:新增经济数据落独立表(`town_economy_daily`/`place_revenue`/`town_stock`/`stock_daily`/`stock_holding`);`agent_employee` 不加冗余列;`ddl-auto:none` → 手工 ALTER,代码侧对缺表降级不阻断结算。(D4/D7/D9)
- **C7**:所有新 API 只读,沿用 owner="world" 共享模式。(D8)
- **C8**:前端只新增经济面板区块+样式优化,不改地图/居民/活动流既有 DOM。(D10)

## Approved Requirements → Test Obligations → Batch

| # | Requirement (SHALL/MUST) | Test Obligation | Batch |
|---|---|---|---|
| R1 | 每日日常消费扣费(非负、不透支、非活跃不消费、每笔入账) | `consume_notOverdraft` / `consume_skipInactive` | 2.2 |
| R2 | 消费按类别归集场所,消费总额==营收总额(守恒) | `consume_splitToPlaces` / `settleDay_conservation` | 2.2, 2.3 |
| R3 | 作品售卖收入与 quality 正相关,入账;无作品无收入 | `sellProduct_incomePositiveWithQuality` | 2.1 |
| R4 | 每日经济快照(totalCoins/收入/支出/营收总额;每日≤1;可查) | `snapshot_totalCoins` / `snapshot_idempotent` | 2.3 |
| R5 | 所有金币变动统一底账、全额记流水(小额也入账、balance==新余额) | `applyDelta_writesFullLedger` | 1.3, 4.1 |
| R6 | 居民日净收入 == 当日 delta 代数和(可为负);可查 | `sumDeltaByAgentAndDate` 断言(含负) | 1.2, 5.1 |
| R7 | 居民月净收入按 sim_date 年月分组,不串月;可查 | `sumDeltaByAgentGroupByMonth` 断言 | 1.2, 5.1 |
| R8 | 收入明细可追溯(delta/reason/balance/simDate) | `findByAgentIdAndSimDateOrderByIdAsc` 断言 | 1.2, 5.1 |
| R9 | 股票每日更新:价格为正、涨跌幅有界、每股每日≤1条 | `price_positiveAndBounded` / `price_onePerDayPerCode` | 3.1 |
| R10 | 居民自主炒股:买不透支、卖不超卖、交易入账 | `buy_noOverdraft` / `sell_noOversell` / `buy_updatesHoldingCost` | 3.2 |
| R11 | 持仓与盈亏:卖出结算已实现盈亏、浮盈可查 | `sell_realizesPnl` | 3.2, 5.1 |
| R12 | 行情/持仓只读展示,玩家本期不交易 | Controller MockMvc(只读端点存在、无交易写口) | 5.1 |
| R13 | 经济面板展示整体金币/收支,空态不报错 | 浏览器验收(有数据/空态) | 6.1 |
| R14 | 展示居民收入+富豪榜(排序、点击见日/月净收入明细) | 浏览器验收 | 6.1 |
| R15 | 展示场所营收 | 浏览器验收 | 6.1 |
| R16 | 展示股票行情(涨跌着色)+ 居民持仓 | 浏览器验收 | 6.1 |
| R17 | 样式优化不回归既有(地图/居民/活动流仍正常) | 浏览器人工回归 | 6.2 |

**覆盖结论**:specs 全部 SHALL/MUST(17 条需求)均已映射到测试义务与批次,无未映射项。

## Execution Batches (依赖顺序,来自 tasks.md)

- **Batch 0** — 迁移 SQL:5 新表 + `agent_transaction` 索引 + 股票种子(交用户手工执行,我不直连 MySQL)。
- **Batch 1** — 实体/仓库(5组)+ `AgentTransactionRepository` 聚合方法(R6/R7/R8)+ `applyDelta`(R5)。依赖 B0。
- **Batch 2** — 经济核心:作品售卖(R3)、日常消费+场所归集(R1/R2)、营收落库+快照(R2/R4)。依赖 B1。
- **Batch 3** — 股市:行情游走+场所联动(R9)、居民炒股+持仓盈亏(R10/R11)。依赖 B1。
- **Batch 4** — 接线:`payWages` 收敛到 applyDelta 移除门槛(R5)、`dailySettlement` 挂载经济+股市。依赖 B2/B3。
- **Batch 5** — `EconomyController` 只读端点(R6/R7/R8/R11/R12)。依赖 B1/B2/B3。
- **Batch 6** — 前端:经济面板(R13~R16)+ 样式优化不回归(R17)。依赖 B5。

## Completion Definitions

- **代码批次(B1~B5)**:`mvn -o clean compile` 通过 + 该批次单元/Controller 测试绿 + 契约不变量断言通过(守恒/不透支/不超卖/快照幂等/净收入聚合)。
- **迁移(B0)**:交付 `migration.sql`;用户确认在 nblm-mysql 执行成功(代码侧对缺表降级)。
- **前端(B6)**:`docker compose up -d --build app` 部署后浏览器逐区块验收 + 既有模块无回归 + `docker logs nblm-app` 无经济/股市异常、无 GLM 限流。

## Review Gates

- 每个已实现的 wave 需 code-reviewer 出具 `ssf execution review` 通过回执后方可进入依赖 wave 或收尾。
- 关闭前:所有已规划 wave 的评审回执均为 pass;delta specs 同步。

## Escalation Rules (回退规划的条件)

- proposal 范围扩张超出 Scope Fence(如加入玩家操盘、真实支付)→ 回 spec-writer 刷新 specs+contract。
- design 约束变化(如允许 LLM 决策、改用汇总表)→ 刷新 design + 本契约。
- 出现 specs 未覆盖的新需求 → 先补 spec,不在实现里"顺手加"。
- **当前无未映射需求、无未决歧义。**
