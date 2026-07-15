# Tasks: 智能体小镇 经济与金融体系

包路径根:`notebooklm-app/src/main/java/io/llmnote/world/`(下称 `world/`)。
前端:`notebooklm-app/src/main/resources/static/index.html`。
迁移:`infra/mysql/init.sql`(建表基线)+ `changes/town-economy-finance/migration.sql`(交付用户手工执行的 ALTER)。
编译:`cd notebooklm-app && mvn -o clean compile`;测试:`mvn -o test`。

## File Structure

**新建(Create)**
- `world/TownEconomyService.java` — 消费/场所营收/作品售卖/月结/经济快照的核心引擎。
- `world/StockMarketService.java` — 股票行情更新 + 居民自主炒股 + 持仓盈亏结算。
- `world/PlaceRevenue.java` / `PlaceRevenueRepository.java` — 场所营收实体+仓库。
- `world/TownEconomyDaily.java` / `TownEconomyDailyRepository.java` — 每日经济快照实体+仓库。
- `world/TownStock.java` / `TownStockRepository.java` — 股票定义实体+仓库。
- `world/StockDaily.java` / `StockDailyRepository.java` — 每日行情实体+仓库。
- `world/StockHolding.java` / `StockHoldingRepository.java` — 居民持仓实体+仓库。
- `world/EconomyController.java` — 经济/股市只读 API。
- `changes/town-economy-finance/migration.sql` — 交付用户手工执行的 ALTER/CREATE。
- 测试:`test/.../world/TownEconomyServiceTest.java`、`StockMarketServiceTest.java`、`ResidentIncomeQueryTest.java`。

**修改(Modify)**
- `world/AgentTransactionRepository.java` — 加净收入聚合与明细查询方法。
- `world/WorldSimEngine.java` — `dailySettlement` 内挂经济/股市调用;`payWages` 收敛到统一 `applyDelta`(移除 ≥100 门槛)。
- `world/WorldController.java` 或新 `EconomyController` — 只读经济端点(采用新 Controller,见 File Structure)。
- `infra/mysql/init.sql` — 新表 CREATE 基线 + `agent_transaction` 索引。
- `static/index.html` — 小镇视图经济面板 + 样式优化。

## Interfaces

跨批次契约(exact types):

```java
// applyDelta:统一金币变动入口(Batch 1 产出,后续批次消费)
// 返回变动后余额;delta 可正可负;无金额门槛;写 agent_transaction(balance=新余额)。
long TownEconomyService.applyDelta(AgentEmployee e, LocalDate date, String reason, long delta);

// 净收入聚合(Batch 2 产出)
long AgentTransactionRepository.sumDeltaByAgentAndDate(Long agentId, LocalDate date);
List<Object[]> AgentTransactionRepository.sumDeltaByAgentGroupByMonth(Long agentId); // [yyyy, mm, sum]
List<AgentTransaction> AgentTransactionRepository.findByAgentIdAndSimDateOrderByIdAsc(Long agentId, LocalDate date);

// 经济核心结算(Batch 3 产出,WorldSimEngine 调用)
void TownEconomyService.settleDay(List<AgentEmployee> active, LocalDate date);
// 内部顺序:作品售卖收入 → 居民日常消费(扣费+归集场所)→ place_revenue 落库 → town_economy_daily 快照

// 股市结算(Batch 4 产出,WorldSimEngine 调用)
void StockMarketService.settleDay(List<AgentEmployee> active, LocalDate date);
// 内部顺序:更新每股 close(有界游走,挂钩当日 place_revenue)→ 居民买卖决策(applyDelta 记账)→ 更新 holding

// 只读查询(Batch 5 产出,Controller 消费)
Optional<TownEconomyDaily> TownEconomyDailyRepository.findTopByOrderBySimDateDesc();
List<PlaceRevenue> PlaceRevenueRepository.findBySimDateOrderByAmountDesc(LocalDate date);
List<StockDaily> StockDailyRepository.findByCodeOrderBySimDateAsc(String code);
List<StockHolding> StockHoldingRepository.findByAgentId(Long agentId);
```

配置常量(集中在 `TownEconomyService`/`StockMarketService` 静态常量或 `world_settings`,首版硬编码保守默认):
`CONSUME_BASE=80, CONSUME_RANGE=120, SELL_K=15, SELL_BASE=20, STOCK_MAX_PCT=0.20`。
消费类别→场所映射:餐饮→`food`(食肆) / 日用→`grocery`(杂货铺) / 医疗→`clinic`(回春医馆) / 娱乐→`market`(集市)。
> 注:落地前用 `grep -n` 核对 `TownMap` 中这四个场所的真实 key 常量,以实际为准。

---

## Batch 0:数据库迁移基线(无代码依赖)

### Task 0.1 — 编写迁移 SQL 与 init.sql 基线
- **文件**:`changes/town-economy-finance/migration.sql`(新建)、`infra/mysql/init.sql`(修改)
- **步骤**:
  1. 在 `init.sql` 末尾(建表区)追加 5 张新表的 `CREATE TABLE`:`town_economy_daily`(sim_date DATE PK)、`place_revenue`(唯一键 sim_date+place_key)、`town_stock`(code PK)、`stock_daily`(唯一键 sim_date+code)、`stock_holding`(唯一键 agent_id+code)。列与注释见 design D4/D7/D9。
  2. 追加 `ALTER TABLE agent_transaction ADD INDEX idx_tx_agent_date (agent_id, sim_date)`。
  3. 追加 `town_stock` 种子 `INSERT`(4~6 只,挂钩 sector:餐饮/文创/医疗/零售…,base_price 正数)。
  4. `migration.sql` = 上述 CREATE/ALTER/INSERT 的可重复执行版本(表用 `CREATE TABLE IF NOT EXISTS`;索引/列用注释说明"若已存在则跳过")。
- **验证**:SQL 语法自查(MySQL 8);不在本步执行(交用户手工跑)。
- **Depends on**: 无

---

## Batch 1:统一底账 + 实体/仓库(依赖 Batch 0 表结构定义)

### Task 1.1 — 新增经济实体与仓库
- **文件**:`world/` 下新建 `PlaceRevenue.java`+`PlaceRevenueRepository.java`、`TownEconomyDaily.java`+`TownEconomyDailyRepository.java`、`TownStock.java`+`TownStockRepository.java`、`StockDaily.java`+`StockDailyRepository.java`、`StockHolding.java`+`StockHoldingRepository.java`
- **TDD**:
  1. **Red**:先写 `TownEconomyDailyRepository` 的 `findTopByOrderBySimDateDesc` 用例(空库返回 empty)。
  2. **Green**:按 design 表结构写 `@Entity`(字段/`@Column(name=...)` 对齐 init.sql 列名),`extends JpaRepository`。
  3. 各仓库补 Interfaces 段声明的查询方法签名。
  4. **Refactor**:统一 `@Data`+`@Entity` 风格,与既有 `AgentTransaction` 一致。
  5. `mvn -o clean compile` 通过。
- **Interfaces**:Produces 上述仓库查询方法。
- **Depends on**: Batch 0

### Task 1.2 — AgentTransactionRepository 聚合方法
- **文件**:`world/AgentTransactionRepository.java`(修改)
- **TDD**:
  1. **Red**:`ResidentIncomeQueryTest`——插入若干流水,断言 `sumDeltaByAgentAndDate` 返回 delta 代数和(含负);`findByAgentIdAndSimDateOrderByIdAsc` 返回明细顺序稳定。
  2. **Green**:加 `@Query("select coalesce(sum(t.delta),0) ...")` 的 `sumDeltaByAgentAndDate`;月分组 `@Query` 返回 `List<Object[]>`(year,month,sum);明细 finder。
  3. **Refactor**:方法命名与既有仓库风格一致。
  4. `mvn -o test -Dtest=ResidentIncomeQueryTest` 通过。
- **Interfaces**:Produces 净收入聚合三方法。
- **Depends on**: 无(纯查询,可与 1.1 并行)

### Task 1.3 — TownEconomyService.applyDelta 统一入口
- **文件**:`world/TownEconomyService.java`(新建,先只放 `applyDelta`)
- **TDD**:
  1. **Red**:`TownEconomyServiceTest#applyDelta_writesFullLedger`——delta=-20 小额也写流水,`balance`=新余额;负 delta 不使 coins<0(封顶到 0)。
  2. **Green**:实现 `applyDelta(e,date,reason,delta)`:`newBal=max(0, coins+delta)`(delta<0 时封顶);实际入账 delta = newBal-oldBal;`e.setCoins(newBal)`+save;写 `AgentTransaction`(无门槛)。
  3. **Refactor**:抽出常量;注释说明"取代 payWages 的 ≥100 门槛"。
  4. 测试通过。
- **Interfaces**:Produces `applyDelta`(供 Batch 3/4)。
- **Depends on**: Task 1.1(需 AgentTransactionRepository 已注入,已存在)

---

## Batch 2:经济核心结算逻辑(依赖 Batch 1)

### Task 2.1 — 作品售卖收入
- **文件**:`world/TownEconomyService.java`(修改)
- **TDD**:
  1. **Red**:`sellProduct_incomePositiveWithQuality`——quality=9 收入 > quality=3;无作品不产生流水。
  2. **Green**:`long sellIncome(AgentEmployee e, LocalDate date, AgentProduct p)` = `round(quality*SELL_K+SELL_BASE)`,经 `applyDelta` 入账 reason="作品售卖:《title》"。
  3. **Refactor**:边界(quality null→0)。
  4. 测试通过。
- **Depends on**: Task 1.3

### Task 2.2 — 居民日常消费 + 场所归集
- **文件**:`world/TownEconomyService.java`(修改)
- **TDD**:
  1. **Red**:`consume_notOverdraft`(余额30应消费120→扣30,coins=0);`consume_splitToPlaces`(消费按类别累加到 food/grocery/clinic/market,四类之和=消费总额);`consume_skipInactive`。
  2. **Green**:`consumeDaily(e,date)` 计算消费额(`CONSUME_BASE+rnd(CONSUME_RANGE)`,余额封顶),按权重拆到四类,经 `applyDelta` 扣费(reason 含类别),并把各类金额累计到一个 `Map<placeKey,Long>` 返回给场所归集。
  3. **Refactor**:类别权重常量化;核对 `TownMap` place key。
  4. 测试通过。
- **Depends on**: Task 1.3

### Task 2.3 — 场所营收落库 + 经济快照
- **文件**:`world/TownEconomyService.java`(修改)
- **TDD**:
  1. **Red**:`settleDay_conservation`——消费总额 == place_revenue 之和;`snapshot_totalCoins`——快照 total_coins == 结算后活跃居民 coins 之和;`snapshot_idempotent`——同 sim_date 已有快照则不重复写。
  2. **Green**:`settleDay(active,date)`:遍历(先 2.1 售卖再 2.2 消费,累计 placeMap 与 income/expense 累加器)→ 每 place 写 `PlaceRevenue`(sim_date+place_key)→ 计算 total_coins/total_income/total_expense/total_place_revenue → 写 `TownEconomyDaily`(存在则跳过)。
  3. **Refactor**:批量 save;缺表/异常降级不抛断结算。
  4. 测试通过。
- **Interfaces**:Produces `settleDay`(供 Batch 5 挂载)。
- **Depends on**: Task 2.1, Task 2.2

---

## Batch 3:股票市场(依赖 Batch 1;与 Batch 2 可并行,但快照顺序在其后)

### Task 3.1 — 行情更新(有界随机游走 + 场所联动)
- **文件**:`world/StockMarketService.java`(新建)
- **TDD**:
  1. **Red**:`price_positiveAndBounded`——单日 change_pct∈[-MAX,+MAX] 且 close>0;`price_onePerDayPerCode`——同 sim_date+code 只一条。
  2. **Green**:`updatePrices(date)`:每只 `town_stock` 取上一 close(无则 base_price),`close=prev*(1+clamp(drift+noise+sectorSignal,±MAX))`,`sectorSignal` 由当日 `place_revenue`(sector→placeKey 映射)派生;写 `StockDaily`(幂等)。
  3. **Refactor**:sector→place 映射常量;noise 用可注入随机以便测试。
  4. 测试通过。
- **Depends on**: Task 1.1

### Task 3.2 — 居民炒股决策 + 持仓盈亏
- **文件**:`world/StockMarketService.java`(修改)
- **TDD**:
  1. **Red**:`buy_noOverdraft`(余额50买单价80→不执行/减量,coins≥0);`sell_noOversell`(持2手卖≤2);`sell_realizesPnl`(成本80×2,现价100全卖→coins+200,持仓归零,流水 delta=+200 reason含卖出);`buy_updatesHoldingCost`。
  2. **Green**:`residentTrade(active,date)`:每居民按余额比例+性格倾向决定买/卖/持有;买入经 `applyDelta(-总价)`+更新 `StockHolding`(加权成本);卖出经 `applyDelta(+卖价×股数)`+减持仓;不透支/不超卖。
  3. **Refactor**:决策规则抽小方法;浮盈计算 `(curPrice-cost)*shares`。
  4. 测试通过。
- **Interfaces**:Produces `settleDay`= `updatePrices`+`residentTrade` 组合。
- **Depends on**: Task 3.1, Task 1.3

---

## Batch 4:接线到每日结算(依赖 Batch 2、3)

### Task 4.1 — payWages 收敛到 applyDelta,移除 ≥100 门槛
- **文件**:`world/WorldSimEngine.java`(修改,`:776` payWages)
- **TDD**:
  1. **Red**(集成级/或轻量):调用工资后小额也应有流水(通过 TownEconomyService.applyDelta 路径)。
  2. **Green**:注入 `TownEconomyService`,`payWages` 内改为 `economy.applyDelta(e,date,reason,amount)`;删掉 `if(amount>=100)` 块。
  3. **Refactor**:确认无其它地方依赖旧门槛。
  4. `mvn -o clean compile`。
- **Depends on**: Task 1.3

### Task 4.2 — dailySettlement 挂载经济与股市结算
- **文件**:`world/WorldSimEngine.java`(修改,`dailySettlement` `:152`)
- **步骤/TDD**:
  1. **Red**:结算后 `town_economy_daily` 有当日快照、`stock_daily` 有当日行情(集成验证或 mock 校验调用顺序)。
  2. **Green**:在既有工资/创作/关系循环之后、写 `world_daily_report` 之前,依次调用 `economy.settleDay(active,date)` → `stockMarket.settleDay(active,date)`。保持整体幂等(已有 `existsBySimDate` 早返回)。
  3. 把经济汇总(total_coins 等)可选写入 `stats` 供日报叙事(不强制)。
  4. **Refactor**:异常隔离——经济/股市失败记 log 不阻断日报落库。
  5. `mvn -o clean compile`。
- **Depends on**: Task 2.3, Task 3.2, Task 4.1

---

## Batch 5:只读 API(依赖 Batch 1~4)

### Task 5.1 — EconomyController 只读端点
- **文件**:`world/EconomyController.java`(新建)
- **TDD**:
  1. **Red**:MockMvc——`GET /api/world/economy/summary` 空库返回空态(非 500);有快照返回 total_coins 等。
  2. **Green**:实现端点(见 design D8):`/economy/summary`、`/economy/places`、`/economy/rich`(按 coins 降序取 top N)、`/agents/{id}/income?range=day|month`(用 Batch 2 聚合;净资产=coins+持仓市值)、`/stocks`(行情+序列)、`/agents/{id}/holdings`(持仓+浮盈)。全部只读,owner="world"。
  3. **Refactor**:DTO 精简;空态统一。
  4. `mvn -o test`(controller 测试)通过。
- **Depends on**: Task 2.3, Task 3.2, Task 1.2

---

## Batch 6:前端(依赖 Batch 5 API 就绪)

### Task 6.1 — 经济面板 + 数据渲染
- **文件**:`static/index.html`(修改,`#worldView` 区 :1012 附近)
- **步骤**:
  1. 在 `#worldView` 内新增经济面板容器(分区:整体金币/收支、富豪榜、场所营收、股市行情、居民持仓);不改地图/居民/活动流既有 DOM。
  2. 新增 fetch+render:`renderEconomySummary`/`renderRich`/`renderPlaceRevenue`/`renderStocks`/`renderHoldings`,对接 Batch 5 端点;空数据显示空态。
  3. 股票涨跌着色(涨红/跌绿或按既有配色),涨跌用不同 class。
  4. 居民点击 → 拉 `/agents/{id}/income` 展开日/月净收入+明细。
- **验证**:`docker compose up -d --build app` 后浏览器进入小镇视图,逐区块确认展示且既有模块无回归(地图/居民/活动流/日报)。
- **Depends on**: Task 5.1

### Task 6.2 — 小镇视图样式优化
- **文件**:`static/index.html`(修改,样式区/worldView CSS)
- **步骤**:
  1. 优化布局层次,让经济面板与地图协调(响应式、卡片化)。
  2. 人工回归既有功能(spec town-economy-ui 的"改版不回归"场景)。
- **验证**:浏览器目视 + 交互既有模块正常。
- **Depends on**: Task 6.1

---

## 验证与交付顺序
1. Batch 0 交付 `migration.sql`,提示用户手工在 nblm-mysql 执行(我不直连)。
2. Batch 1~5 每批 `mvn -o clean compile` / `mvn -o test` 绿。
3. Batch 6 后 `set -a && . ./.env && set +a && docker compose up -d --build app` 部署,浏览器验收。
4. 观察 `docker logs nblm-app`:结算日经济/股市无异常、无 GLM 限流(经济/股市全程零 LLM)。
5. 单元测试守住不变量:金额守恒、不透支、不超卖、快照幂等、净收入聚合正确。

## 需求→任务映射
- town-economy-core:日常消费(2.2)、消费→营收守恒(2.2/2.3)、作品售卖(2.1)、资金池快照(2.3)、全额入账(1.3/4.1)。
- resident-income:日/月净收入(1.2)、明细(1.2)、API(5.1)。
- town-stock-market:行情(3.1)、决策/不透支/不超卖(3.2)、持仓盈亏(3.2)、只读展示(5.1)。
- town-economy-ui:面板/收支/富豪榜/场所/行情/持仓(6.1)、样式优化不回归(6.2)。
