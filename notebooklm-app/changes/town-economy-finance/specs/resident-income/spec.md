# Spec: resident-income

居民个人日/月净收入的聚合与展示。净收入 = 当期收入(工资+售卖+炒股盈利+事件所得)- 当期支出(日常消费+炒股亏损+事件损失),数据来源为统一底账 `agent_transaction`。

## ADDED Requirements

### Requirement: 居民每日净收入聚合
系统 SHALL 为每位活跃居民在每日结算后聚合出当日净收入(当日全部 `agent_transaction.delta` 之和),并可通过只读 API 按居民查询。当日净收入 MUST 等于该居民当日所有流水 `delta` 的代数和。

#### Scenario: 收入减支出得净额
- WHEN 某居民当日流水为 +150(工资)、+80(售卖)、-120(消费)
- THEN 其当日净收入为 +110

#### Scenario: 净收入可为负
- WHEN 某居民当日流水为 +60(工资)、-200(消费+炒股亏损)
- THEN 其当日净收入为 -140(允许为负)

### Requirement: 居民每月净收入聚合
系统 SHALL 提供每位居民按月(以 `sim_date` 的年月为口径)的净收入聚合,MUST 等于该居民该自然月内全部流水 `delta` 之和,并可通过只读 API 查询指定居民的月度序列。

#### Scenario: 跨日累计到月
- WHEN 某居民在某月内有 20 个结算日,每日净收入之和为 +2200
- THEN 该居民该月净收入为 +2200

#### Scenario: 按月分组互不串月
- WHEN 某居民在 6 月与 7 月各有流水
- THEN 6 月净收入只统计 6 月流水,7 月同理,互不混入

### Requirement: 收入明细可追溯
针对某居民某日的净收入,系统 SHALL 能返回构成该净收入的流水明细(至少含 `delta`、`reason`、`balance`、`simDate`),以支持前端展示"钱从哪来/花到哪去"。

#### Scenario: 展开当日明细
- WHEN 前端请求某居民某 `simDate` 的收入明细
- THEN 返回该日该居民的全部 `agent_transaction` 记录列表(时间/顺序稳定)
