# points-growth 规格:运营赠分与会员等级

## ADDED Requirements

### Requirement: 赠分规则配置化
运营规则 SHALL 数据库配置化:规则类型(SIGNUP/CHECKIN/INVITE/ACTIVITY)、赠分额度、开关、每日全站发放上限、生效时间窗。修改规则 MUST 无需发版即时生效。

#### Scenario: 调整签到赠分
- **WHEN** 管理员将 CHECKIN 规则额度从 10 改为 20 并保存
- **THEN** 此后签到按 20 发放,此前流水不变

### Requirement: 注册赠分
系统 SHALL 在新用户开户时按 SIGNUP 规则发放赠分,写一条 SIGNUP 流水,bizRef 关联用户 ID 保证只发一次。

#### Scenario: 新用户获得注册赠分
- **WHEN** 用户注册成功且 SIGNUP 规则开启、额度 100
- **THEN** 账户余额 100,存在一条 SIGNUP 流水

### Requirement: 每日签到
系统 SHALL 提供签到接口:每用户每自然日限一次,按 CHECKIN 规则赠分并记录连续签到天数(streak)。重复签到 MUST 拒绝;连续中断后 streak 从 1 重计。

#### Scenario: 首次签到
- **WHEN** 用户当日首次调用签到接口
- **THEN** 按规则赠分,写 CHECKIN 流水,streak 加 1

#### Scenario: 当日重复签到
- **WHEN** 用户当日第二次调用签到接口
- **THEN** 请求被拒绝,余额与 streak 不变

### Requirement: 邀请返利
系统 SHALL 为每个用户生成唯一邀请码。被邀请人注册时填写邀请码,开户后双方各按 INVITE 规则获赠分。同一被邀请人 MUST 只触发一次返利;邀请人与被邀请人相同(自邀请)MUST 拒绝。

#### Scenario: 有效邀请
- **WHEN** 新用户 B 注册时填了用户 A 的邀请码
- **THEN** A、B 各得一条 INVITE 流水,bizRef 含双方 ID

#### Scenario: 自邀请
- **WHEN** 用户注册时填自己的邀请码
- **THEN** 返利不触发,无 INVITE 流水

### Requirement: 活动赠分
系统 SHALL 支持管理端按单个用户或全员发放活动赠分,写 ACTIVITY 流水,remark 记录活动标识与操作人。全员发放 MUST 批处理且可断点续跑。

#### Scenario: 全员活动赠分
- **WHEN** 管理员发起全员 50 积分活动赠分
- **THEN** 每个用户收到一条 ACTIVITY 流水,中断重跑时已发放用户不重复入账

### Requirement: 会员等级与权益
系统 SHALL 按累计充值 totalRecharged 阈值划分等级(阈值与权益配置化),权益含消耗折扣率 levelDiscount 与赠分倍率。等级 MUST 在充值到账后实时重算生效;计量扣费时 SHALL 应用当前等级折扣。

#### Scenario: 升级即时生效
- **WHEN** 用户 totalRecharged 跨过 L2 阈值
- **THEN** 其等级即时变 L2,此后计量扣费按 L2 折扣率折算

#### Scenario: 折扣参与折算
- **WHEN** L2 用户(折扣 0.8)发生一次折算前 10 积分的调用
- **THEN** 实扣 ceil(10 × 0.8) = 8 积分
