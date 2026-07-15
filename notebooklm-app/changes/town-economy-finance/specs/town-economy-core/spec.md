# Spec: town-economy-core

小镇经济核心:整体资金池、居民每日日常消费、场所营收、作品售卖收入、经济健康统计。所有金额均为虚拟金币(整数)。所有变动统一写入 `agent_transaction` 底账。

## ADDED Requirements

### Requirement: 每日日常消费扣费
每个每日结算(`dailySettlement`)中,每位 `status=active` 的居民 SHALL 产生一笔当日日常消费,并从其 `coins` 余额中扣除;消费金额 MUST 为非负整数,且 MUST NOT 使余额变为负数(余额不足时按余额封顶扣除)。每笔消费 MUST 写入 `agent_transaction`(`delta` 为负,`reason` 标注消费类别)。

#### Scenario: 正常居民产生日常消费
- WHEN 一位余额为 500 的活跃居民参与当日结算
- THEN 系统为其计算一笔日常消费(如 120),将 `coins` 扣至 380
- AND 写入一条 `agent_transaction`,`delta=-120`、`balance=380`、`reason` 含消费类别

#### Scenario: 余额不足时不透支
- WHEN 一位余额为 30 的居民当日应消费 120
- THEN 实际扣除不超过 30,`coins` 结果为 0(不为负)
- AND 写入的流水 `delta` 与实际扣除额一致

#### Scenario: 非活跃居民不消费
- WHEN 一位 `status=jailed`(或已死亡)的居民参与当日
- THEN 系统不为其产生日常消费,`coins` 不因消费变化

### Requirement: 消费转化为场所营收
每日居民的日常消费金额 SHALL 按消费类别归集到对应的商业场所(`TownMap` 中的 food/grocery/clinic/market 等),形成当日场所营收记录。全部居民当日消费总额 MUST 等于全部场所当日营收之和(金额守恒)。

#### Scenario: 消费流向对应场所
- WHEN 当日全部居民产生消费:吃饭类合计 800、购物类合计 500、就医类合计 200
- THEN 食肆当日营收记为 800、杂货铺 500、回春医馆 200
- AND 三者之和 1500 等于当日居民消费总额

#### Scenario: 场所营收按日聚合可查
- WHEN 某场所在某 `simDate` 有营收
- THEN 存在一条该场所该日期的营收记录,可通过 API 只读查询

### Requirement: 作品售卖收入
当居民在当日创作出作品(`agent_product`)时,系统 SHALL 按作品质量(`quality`)折算一笔售卖收入并计入该居民 `coins`,同时写入 `agent_transaction`(`delta` 为正,`reason` 标注售卖)。售卖收入 MUST 与作品质量正相关。

#### Scenario: 高质量作品收入更高
- WHEN 居民 A 产出 quality=9 的作品,居民 B 产出 quality=3 的作品
- THEN A 获得的售卖收入 MUST 大于 B 获得的售卖收入
- AND 各自写入一条 `delta>0`、`reason` 含"售卖"的流水

#### Scenario: 当日无作品则无售卖收入
- WHEN 某居民当日未产出任何作品
- THEN 不为其产生售卖收入流水

### Requirement: 小镇整体资金池与经济健康统计
系统 SHALL 在每日结算后计算并持久化当日小镇经济快照,至少包含:全体活跃居民金币总量(`totalCoins`)、当日总收入、当日总支出、当日场所营收总额。该快照 MUST 每个 `simDate` 至多一条,且可通过只读 API 获取最新值与历史序列。

#### Scenario: 生成当日经济快照
- WHEN 某 `simDate` 的每日结算完成
- THEN 存在该日期唯一一条经济快照,`totalCoins` 等于当日结算后全体活跃居民 `coins` 之和
- AND 记录当日总收入、总支出、场所营收总额

#### Scenario: 快照幂等(重复结算不重复写)
- WHEN 同一 `simDate` 已存在经济快照且再次触发结算
- THEN 不新增重复快照(沿用既有幂等规则:`existsBySimDate`)

### Requirement: 收支通过统一底账记录
本能力引入的所有金币变动(工资、售卖、消费、场所结算相关)SHALL 通过统一路径写入 `agent_transaction`,使 `balance` 字段始终等于该次变动后的 `coins`。区别于既有"仅 ≥100 才记流水"的策略,消费与售卖 MUST 无论金额大小都记流水(以支撑日/月收入聚合的准确性)。

#### Scenario: 小额消费也入账
- WHEN 一位居民产生一笔 20 金币的小额消费
- THEN 该笔仍写入 `agent_transaction`(不因 <100 被丢弃)
- AND 其 `balance` 等于扣款后的 `coins`
