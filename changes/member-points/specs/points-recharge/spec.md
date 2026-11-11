# points-recharge 规格:充值套餐与订单

## ADDED Requirements

### Requirement: 套餐管理
系统 SHALL 支持管理端配置充值套餐:名称、积分额度、价格(分)、赠倍率(bonusRate)、状态(上架/下架)、排序。用户端 MUST 只能查到上架套餐,按排序返回。

#### Scenario: 用户查询套餐列表
- **WHEN** 用户请求 GET /api/points/packages
- **THEN** 仅返回上架套餐,含名称、积分、价格、赠倍率,无下架套餐

### Requirement: 创建充值订单
用户选择套餐后系统 SHALL 创建订单:orderNo(全局唯一)、userId、packageId、points(含赠送后总额)、amount、status=PENDING、createdAt。订单状态机 MUST 为 PENDING → PAID / CANCELLED,禁止逆向或跳跃。

#### Scenario: 下单成功
- **WHEN** 用户对上架套餐下单,套餐 1000 积分、赠倍率 0.2
- **THEN** 创建 PENDING 订单,points=1200,amount 等于套餐价格

#### Scenario: 对下架套餐下单
- **WHEN** 用户对已下架套餐下单
- **THEN** 请求被拒绝,不产生订单

### Requirement: 兑换码充值
系统 SHALL 支持管理端批量生成兑换码(码值唯一、关联套餐、过期时间、使用状态)。用户提交兑换码时:有效则创建订单并直接置 PAID 到账;码不存在、已使用或已过期 MUST 拒绝且不产生任何流水。

#### Scenario: 有效兑换码
- **WHEN** 用户提交有效兑换码
- **THEN** 创建 PAID 订单,账户增加对应积分,写一条 RECHARGE 流水,兑换码标记已使用且绑定该用户

#### Scenario: 重复使用兑换码
- **WHEN** 同一兑换码被第二次提交
- **THEN** 请求被拒绝,账户余额与流水无变化

### Requirement: 管理端人工到账
系统 SHALL 支持管理端对 PENDING 订单标记到账(线下收款场景),到账 MUST 与用户余额变更、流水写入在同一事务完成。

#### Scenario: 人工确认到账
- **WHEN** 管理员对一笔 PENDING 订单确认到账
- **THEN** 订单置 PAID,账户 balance 与 totalRecharged 增加,写一条 RECHARGE 流水,三者同时成功或同时回滚

### Requirement: 到账幂等
同一订单 MUST 只到账一次。任何到账路径(兑换码、人工、未来支付回调)重复触发时,系统 SHALL 识别订单已 PAID 并直接返回成功,不重复入账。

#### Scenario: 重复到账回调
- **WHEN** 对一笔已 PAID 订单再次触发到账
- **THEN** 返回原订单结果,账户与流水无任何新增变动
