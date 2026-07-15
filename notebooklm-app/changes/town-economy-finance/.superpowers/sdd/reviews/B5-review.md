# Wave B5 Review — 只读经济 API

- **Wave**: B5 (Task 5.1)
- **Range**: 34040b2..working-tree(未提交)
- **Verdict**: pass

## 交付物
- `EconomyController`(新建,`/api/world` 下 6 只读端点)。
- `EconomyControllerTest`(standalone MockMvc)6 用例全绿。

## Spec 合规(design D8)
- `GET /economy/summary` — 最新快照 + 近30日序列(时间正序)。空库返回 `latest:null`+空数组,非 500。✅
- `GET /economy/places?date=` — 场所营收(缺省取最新快照日),含中文名映射。✅
- `GET /economy/rich?limit=` — 富豪榜按 coins 降序 top N,含净资产。✅
- `GET /agents/{id}/income?range=day|month` — 日净收入+明细 / 月度汇总;未知居民 `found:false`。✅
- `GET /stocks` — 每股行情 + 近60日序列 + changePct。✅
- `GET /agents/{id}/holdings` — 持仓 + 浮盈((现价-均价)*股数)。✅
- 全部只读、owner="world" 共享(无 Principal 依赖);/api/world 无逐路由授权,沿用现有访客兜底。✅

## Spec 合规(聚合)
- **R8 净资产**:coins + Σ(持仓现价×股数),`netWorth()` 复用于 rich/income。✅
- **月收入**:复用 B1 `sumDeltaByAgentGroupByMonth`([year,month,net])。✅

## 关键修复
- `rich()` 原地 sort 触发 `UnsupportedOperationException`(仓库返回不可变列表)→ 拷入 `new ArrayList<>` 再排序。
- `rich` top N 边界:空库时 `n=min(max(1,limit),0)=0`,避免越界访问。

## 编译/测试
- `mvn -o clean compile` 通过。
- `mvn -o test -Dtest=EconomyControllerTest` → Tests run: 6, Failures: 0, Errors: 0(含空态非500断言)。

## 发现
- 无 Critical / Important。
- Minor:date 解析失败静默回退最新快照日(容错优先,符合只读展示语义)。

## 结论
6 个只读端点齐备,空态健壮(非500),净资产/月收入聚合正确;编译测试通过。B5 通过,解锁 B6 前端。
