# Wave B0 Review — 迁移 SQL + init.sql 基线

- **Wave**: B0 (Task 0.1)
- **Range**: 34040b2..34040b2 (工作树未提交;变更为 migration.sql 新建 + init.sql 修改)
- **Verdict**: pass

## 交付物
- `changes/town-economy-finance/migration.sql`(新建)— 交用户手工在 nblm-mysql 执行。
- `infra/mysql/init.sql`(修改)— 新装基线含同结构。

## Spec 合规
- 契约 C6:5 张新表(`town_economy_daily`/`place_revenue`/`town_stock`/`stock_daily`/`stock_holding`)+ `agent_transaction` 复合索引 `idx_txn_agent_date`。✅ 全部到位(init.sql:370/457/468/479/489/502)。
- 幂等键:`town_economy_daily` PK=sim_date;`place_revenue` uk(sim_date,place_key);`stock_daily` uk(sim_date,code);`stock_holding` uk(agent_id,code);`town_stock` PK=code。✅ 支撑 R2/R4/R9/R10 的"每日/每股/每人至多一条"。
- 可重复执行:表 `CREATE TABLE IF NOT EXISTS`;种子 `INSERT IGNORE`;索引 ALTER 注明重复执行报 1061 可忽略(MySQL 8 无 ADD INDEX IF NOT EXISTS)。✅
- place_key 对齐 `TownMap` 真实 key:餐饮=`restaurant`(非 food)、`grocery`/`clinic`/`market`。✅ 已 grep 核对并在注释标注。

## 发现
- 无 Critical / Important。
- Minor(记录不阻断):`stock_daily.change_pct` 用 DECIMAL(6,4),范围 ±20% 足够;后端写入需按此精度四舍五入(留给 B3 实现)。

## 结论
迁移脚本与基线结构一致、幂等、语法自查通过;不在本步执行 DDL(交用户手工跑)。B0 通过,可解锁 B1。
