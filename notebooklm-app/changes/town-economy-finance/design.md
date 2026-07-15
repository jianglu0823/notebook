# Design: 智能体小镇 经济与金融体系

## Context

### 现状
- **结算入口**:`WorldSimEngine.dailySettlement(LocalDate)`(`WorldSimEngine.java:152`)是唯一的每日经济节拍,由 `AutonomousRunner` 触发,`existsBySimDate` 保证每日幂等(`:153`)。
- **金币收入**:`payWages(e, date, reason, 60+rnd(120))`(`:169`、`:776`)每人每日发一笔随机工资;随机事件加减(`:567`/`:580`)。
- **底账**:`agent_transaction`(实体 `AgentTransaction`,字段 `agentId/simDate/delta/balance/reason/createdAt`),但 `payWages` **仅 `amount>=100` 才写流水**(`:781`),小额不入账。
- **场所**:`TownMap` 已定义 food(食肆)/grocery(杂货铺)/clinic(回春医馆)/market(集市)等 15 个地点,但**纯地图标记,无营收逻辑**。
- **作品**:`agent_product`(`agentId/simDate/kind/title/content/quality`),`maybeCreate` 每日至多产一件(`:176`),**当前不产生收入**。
- **快照**:`world_daily_report`(季节/天气/highlightsJson/statsJson)——有每日报告表,但 stats 不含经济口径。
- **前端**:小镇视图 `#worldView`(index.html:1012),地图 `#worldMap`(:408),无经济展示。
- **API**:`WorldController` 提供 agents/places/reports/gallery/activity/settings 只读端点。

### 约束
- **DB `ddl-auto:none`**:所有新表/新列须手工 ALTER(交付 SQL 由用户在 nblm-mysql 执行,不由我直连)。
- **结算内 LLM 成本/限流**:结算已调用 GLM 写日报/评论;炒股决策若逐人调 LLM 会显著增 token 与触发限流。
- **纯虚拟金币,整数**;玩家本期只观看股市,不交易。
- 不重写漫游/自主引擎,只在 `dailySettlement` 内挂经济逻辑。

### Stakeholders
用户(小镇主理人,观看经济演化);居民 AI(消费/创作/炒股的行为主体)。

## Goals
1. 让经济**循环**:收入(工资+售卖+炒股)与支出(日常消费+炒股亏损)对冲,金币不再只增。
2. 场所成为**营收体**,消费金额守恒地流向场所。
3. 居民**日/月净收入**可聚合、可追溯,基于统一底账。
4. 引入**股票市场**,居民自主交易,持仓与盈亏可查。
5. 前端**可视化**经济数据,且不回归既有小镇功能。
6. 结算**幂等**、**低 LLM 成本**、数值**可配置防失衡**。

## Decisions

### D1. 经济逻辑挂在 dailySettlement 内,新增独立 EconomyEngine 承载
**Choice**:新建 `TownEconomyService`(或 `EconomyEngine`)承载"消费/营收/售卖/月结/快照",`WorldSimEngine.dailySettlement` 在既有工资/创作循环之后调用它;股市另建 `StockMarketService`,同样由结算调用。
**Rationale**:`WorldSimEngine` 已近千行,职责过载;拆分让经济/股市可独立测试与调参。结算作为唯一节拍保证幂等与顺序(先发工资/创作→再售卖→再消费→再场所营收→再股市→最后快照)。
**Alternatives**:全塞进 `WorldSimEngine`(否决:膨胀、难测);独立定时任务(否决:与结算竞争、幂等复杂、日期不一致)。

### D2. 统一底账 = agent_transaction,取消"仅≥100记流水",改为全额记
**Choice**:消费/售卖/炒股一律通过一个统一方法 `applyDelta(e, date, reason, delta)` 改 `coins` 并写 `agent_transaction`(`balance` = 变动后余额),无金额门槛。既有 `payWages` 收敛到同一路径。
**Rationale**:日/月净收入(resident-income)必须精确到每笔,`≥100` 门槛会漏掉小额消费导致聚合失真(spec 明确要求小额也入账)。
**Alternatives**:保留门槛另存消费表(否决:两套账难对齐、金额守恒校验困难)。
**迁移影响**:流水量增大——`agent_transaction` 加索引 `(agent_id, sim_date)` 支撑聚合查询。

### D3. 日/月净收入用聚合查询,不建冗余汇总表
**Choice**:日净收入 = `SELECT SUM(delta) ... WHERE agent_id=? AND sim_date=?`;月净收入 = 按 `YEAR(sim_date),MONTH(sim_date)` 分组求和。用 JPA `@Query` 实现,不落地额外汇总表。
**Rationale**:底账已是权威来源,聚合是纯派生;避免"双写不一致"。数据量(居民数×天数)在小镇规模下完全可即时聚合。
**Alternatives**:每日结算写 `resident_daily_income` 汇总表(否决:冗余、与底账可能漂移;若未来数据量大再加物化视图)。

### D4. 场所营收落地为 place_revenue 表,消费按类别→场所映射
**Choice**:新表 `place_revenue(id, sim_date, place_key, amount, created_at)`,每日每场所一条。消费类别→场所固定映射:餐饮→food、日用→grocery、医疗→clinic、娱乐→market。当日结算把居民消费按类别累加到对应 place。
**Rationale**:金额守恒(消费总额=营收总额)易校验;place_key 复用 `TownMap` 既有 key,前端可直接对齐地图。
**Alternatives**:营收并入 world_daily_report 的 JSON(否决:难按场所查询/画图)。

### D5. 居民日常消费:规则化计算,不调 LLM
**Choice**:消费额 = 基础日常 + 按 `energy`/`coins`/随机扰动 的规则函数(如 `base + rnd(range)`,富者多花、余额封顶不透支),类别按权重分配到餐饮/日用/医疗/娱乐。
**Rationale**:每人每日都要算,LLM 成本不可接受;规则可控可调参。人设风味留给"炒股倾向"等少量决策。
**Alternatives**:LLM 生成消费清单(否决:成本/限流,spec 无此要求)。

### D6. 作品售卖收入:质量线性折算,规则化
**Choice**:`maybeCreate` 产出作品后,`income = round(quality * K + base)`(K 可配),通过 `applyDelta` 计入,reason="作品售卖:《title》"。
**Rationale**:spec 要求与 quality 正相关且可测;线性最简单可验证。
**Alternatives**:按 kind 差异化定价(可作为配置项后续加)。

### D7. 股市:规则化价格模型 + 有界随机游走,居民决策"轻LLM/零LLM"
**Choice**:
- 股票定义表 `town_stock(code, name, sector, base_price)`,种子若干只(挂钩场所/行业:餐饮/文创/医疗/零售…)。
- 价格历史表 `stock_daily(id, sim_date, code, open, close, change_pct)`,每日每股一条。
- 价格模型:`close = prev * (1 + clamp(drift + noise + sectorSignal, -maxPct, +maxPct))`,`maxPct` 配置默认 0.20;`sectorSignal` 可挂钩对应场所当日营收(让经济与股市联动),但**不调 LLM**。
- 居民决策:**规则化**为主(按余额比例、随机倾向、性格标签 momentum/contrarian);默认零 LLM。持仓表 `stock_holding(id, agent_id, code, shares, cost)`。
- 交易通过 `applyDelta` 记金币流水;买不透支、卖不超卖;卖出按(卖价-成本)结算已实现盈亏。
**Rationale**:每日全镇×多股的决策若走 LLM 成本爆炸;规则模型可测(spec 场景全部可验)、可调、与经济联动。
**Alternatives**:LLM 逐人决策(否决:成本/限流);真实随机数据源(不适用)。
**可选增强**:给"炒股倾向标签"留 persona 派生位,后续可小成本引入。

### D8. API:WorldController 下新增只读经济端点
**Choice**:新增
- `GET /api/world/economy/summary`(最新经济快照+历史序列)
- `GET /api/world/economy/places`(场所营收,当日/历史)
- `GET /api/world/economy/rich`(富豪榜)
- `GET /api/world/agents/{id}/income?range=day|month`(居民日/月净收入+明细)
- `GET /api/world/stocks`(行情+近期序列)、`GET /api/world/agents/{id}/holdings`(持仓+浮盈)
全部只读,沿用现有 owner="world" 共享模式。
**Rationale**:与既有 `/api/world/*` 一致,前端改动集中。
**Alternatives**:GraphQL/聚合大接口(否决:超范围)。

### D9. 经济快照落地为新表 town_economy_daily,不塞进 world_daily_report
**Choice**:`town_economy_daily(sim_date PK, total_coins, total_income, total_expense, total_place_revenue, created_at)`,每日一条,幂等随结算。
**Rationale**:经济口径独立演进,避免污染日报叙事结构;PK=sim_date 天然幂等。
**Alternatives**:扩 world_daily_report 列(否决:语义混杂)。

### D10. 前端:小镇视图内新增"经济"子面板,复用现有 fetch/render 模式
**Choice**:在 `#worldView` 内新增经济面板容器与 Tab/分区(整体金币/收支、富豪榜、场所营收、股市行情、居民持仓),用既有 `renderXxx`/轮询风格拉取新只读 API;不改地图/居民/活动流的既有 DOM 结构,只做样式优化与新增区块。
**Rationale**:spec 要求不回归既有功能;增量式最安全。
**Alternatives**:重写整个 worldView(否决:回归风险高)。

## 新增数据表汇总(手工 ALTER,交付 SQL)
| 表 | 关键列 | 幂等键 | 用途 |
|---|---|---|---|
| `town_economy_daily` | sim_date, total_coins, total_income, total_expense, total_place_revenue | sim_date | 每日经济快照 |
| `place_revenue` | sim_date, place_key, amount | (sim_date, place_key) | 场所营收 |
| `town_stock` | code, name, sector, base_price | code | 股票定义(种子) |
| `stock_daily` | sim_date, code, open, close, change_pct | (sim_date, code) | 每日行情 |
| `stock_holding` | agent_id, code, shares, cost | (agent_id, code) | 居民持仓 |
| `agent_transaction`(改) | 新增索引 (agent_id, sim_date) | — | 支撑净收入聚合 |

`agent_employee` 本期**不加冗余列**(富豪榜按 `coins` 排序即可;净资产=coins+持仓市值在查询层算)。

## Risks And Trade-Offs
- **结算耗时/流水膨胀**:全额记流水 + 多表写入使单次结算变重。缓解:批量保存、给聚合列加索引、股市/消费均规则化零 LLM。
- **数值失衡(通胀/破产)**:收入>支出会通胀,反之集体破产。缓解:关键系数(工资区间、消费基数、售卖 K、股票 maxPct)集中为可配置常量/`world_settings`,首版给保守默认并观察日报快照曲线。
- **手工迁移一致性**:6 处 DDL 变更需用户执行;交付单一 SQL 脚本 + 幂等 `IF NOT EXISTS`(MySQL 8 列级用存在性检查/文档说明),并在代码侧对缺表做降级(查询失败不阻断结算)。
- **金额守恒回归**:消费=营收、买不透支/卖不超卖等不变量,用单元测试守住(spec 场景直接映射为断言)。
- **前端回归**:改版可能碰坏地图/活动流。缓解:只新增区块+样式,既有 DOM/JS 尽量不动,改后人工过一遍小镇视图。
