# Wave B3 Review — 股票市场

- **Wave**: B3 (Tasks 3.1, 3.2)
- **Range**: 34040b2..working-tree(未提交)
- **Verdict**: pass

## 交付物
- `StockMarketService`(新建):`updatePrices(date)` 行情、`residentTrade(active,date)` 炒股、`settleDay(active,date)` 组合;可注入 `Random`。
- `StockMarketServiceTest` 6 用例全绿。

## Spec 合规
- **R9 行情有界随机游走**:close=prev*(1+clamp(drift+noise+sectorSignal,±MAX));change_pct∈[-0.20,0.20] 且 close>0。✅(`price_positiveAndBounded`)
- **每日每股一条(幂等)**:`findBySimDateAndCode` upsert,已有行复用不新建。✅(`price_onePerDayPerCode_upsert`)
- **场所联动**:sector→place 映射(catering→restaurant/medical→clinic/retail→grocery/culture→market),当日营收≥pivot 给正信号。✅
- **R10 买不透支**:预算=余额/5,买不起(余额<单价)不下单;50 轮交易后 coins≥0。✅(`buy_noOverdraft`)
- **R10 卖不超卖 + 实现盈亏**:成本160(80×2)现价100 全卖 → applyDelta(+200)、持仓 shares/cost 归零。✅(`sell_realizesPnl_andZerosHolding`);无持仓卖出 noop(`sell_noHolding_noop`)。
- **加权成本更新**:余额1000 买2手@100 → holding shares=2, cost=200。✅(`buy_updatesHoldingCost`)

## 关键实现说明
- 全程零 LLM,规则驱动(design D7)。
- `Random` 可注入:测试用固定种子 + 匿名子类强制 buy/sell 分支,覆盖交易路径确定性。
- 买入封顶找零:applyDelta 扣费被封顶导致少买时退回零头,保证持仓成本==实付。
- 卖出采用全卖简单规则(shares→0, cost→0),浮盈由 B5 只读端点按现价×股数-cost 计算。

## 编译/测试
- `mvn -o test -Dtest=StockMarketServiceTest` → Tests run: 6, Failures: 0, Errors: 0。

## 发现
- 无 Critical / Important。
- Minor:决策目前每人每日随机挑一只股 + 三选一动作(持/买/卖),倾向简化未挂钩性格;满足本批"村民可炒股"最小闭环,后续可增强。

## 结论
行情与炒股两条链路齐备,边界(不透支/不超卖/幂等/有界)经确定性单测覆盖;编译测试通过。B3 通过,解锁 B4。
