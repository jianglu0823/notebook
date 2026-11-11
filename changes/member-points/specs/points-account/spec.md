# points-account 规格:积分账户与流水

## ADDED Requirements

### Requirement: 注册自动开户
系统 SHALL 在用户注册成功时为该用户创建唯一积分账户,账户初始余额为 0,注册赠分由 points-growth 规则另行发放。开户 MUST 幂等,重复触发不产生第二个账户。

#### Scenario: 新用户注册成功
- **WHEN** 用户在 /api/auth/register 注册成功
- **THEN** 系统为该用户创建积分账户,balance=0、frozen=0、totalRecharged=0、totalConsumed=0

#### Scenario: 重复开户触发
- **WHEN** 对已存在账户的用户再次触发开户逻辑
- **THEN** 系统返回既有账户,不新建、不报错

### Requirement: 存量用户一次性初始化
系统 SHALL 提供一次性初始化任务,为所有存量用户开户并按配置发放启动赠分,已开户用户跳过。

#### Scenario: 迁移执行
- **WHEN** 管理员执行存量用户初始化任务
- **THEN** 每个无账户用户获得账户与一条 INIT 类型流水,已有账户用户无任何变动

### Requirement: 账户字段完整性
账户 MUST 持久化 balance(可用余额)、frozen(预冻结中)、totalRecharged(累计充值)、totalConsumed(累计消耗)、version(乐观锁版本号)。任何余额变动 MUST 在同一事务内更新账户并写流水。

#### Scenario: 扣减后字段一致
- **WHEN** 一笔 100 积分的消耗实结完成
- **THEN** balance 减 100、totalConsumed 加 100,且存在一条对应流水,balanceAfter 等于账户 balance

### Requirement: 流水只增不改
每笔余额或冻结变动 MUST 写入一条流水,字段含 type、amount(正为入负为出)、balanceAfter、bizRef(业务单号)、remark、createdAt。流水 MUST 只允许插入,禁止更新与删除。

#### Scenario: 流水查询完整
- **WHEN** 用户发生充值、消耗、赠分各一笔
- **THEN** 流水中存在 RECHARGE、CONSUME、赠分类三条记录,金额符号正确,balanceAfter 链式连续

### Requirement: 并发扣减一致性
同一用户并发扣减 MUST 通过数据库条件更新或乐观锁保证 balance 不出现非授权负值;余额不足的扣减 MUST 整体失败回滚。

#### Scenario: 并发扣减超过余额
- **WHEN** 余额 100,两笔各 80 的扣减并发提交
- **THEN** 恰好一笔成功,另一笔失败且账户余额与流水仅反映成功那笔

### Requirement: 账户与流水查询接口
系统 SHALL 提供 GET /api/points 返回当前用户 balance、frozen、totalRecharged、totalConsumed、level;GET /api/points/transactions 分页返回流水,按 createdAt 倒序,支持 type 过滤。接口 MUST 只返回当前登录用户数据。

#### Scenario: 查询他人数据被拒绝
- **WHEN** 用户 A 请求积分接口
- **THEN** 响应仅含用户 A 的账户与流水,无任何其他用户数据
