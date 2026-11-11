# points-metering 规格:token 计量与扣费

## ADDED Requirements

### Requirement: 统一计量收口
所有 LLM 调用 MUST 经由计量组件统一收口(以 ChatCompletion / ChatModelFactory 为接入点),禁止业务代码绕过计量直接调模型。每次调用 MUST 落一条 usage 记录:userId、model、feature(伴侣/小世界/创作/问答/生成/语音)、inputTokens、outputTokens、points、status、bizRef、createdAt。

#### Scenario: 伴侣聊天一次调用
- **WHEN** 用户在伴侣聊天发送一条消息并完成模型回复
- **THEN** 存在一条 usage 记录,feature=companion,token 数与模型返回的 usage 一致,status=SETTLED

### Requirement: 积分折算规则
系统 SHALL 按公式折算积分:points = ceil((inputTokens × inputPer1k + outputTokens × outputPer1k) / 1000 × levelDiscount)。付费模型按 ChatModelFactory 配置的真实单价;GLM 免费通道模型 SHALL 按配置的名义费率折算,该费率 MUST 显著低于最低付费模型单价(默认不超过其 1/10)。最低计费单位为 1 积分,非零用量 MUST 至少计 1 积分。

#### Scenario: 付费模型折算
- **WHEN** 某次调用输入 2000 token、输出 1000 token,模型单价输入 0.0008 元/千 token、输出 0.002 元/千 token,1 积分 = 0.001 元
- **THEN** 成本 = 2×0.0008 + 1×0.002 = 0.0036 元,折算 ceil(3.6) = 4 积分,写入 usage 记录

#### Scenario: 免费模型低费率
- **WHEN** 某次调用用 GLM 免费通道模型
- **THEN** 按名义费率折算,同等 token 下积分不高于付费模型的 1/10,且至少 1 积分

### Requirement: 调用前预冻结
系统 SHALL 在调用模型前按该功能的预估上限冻结积分(余额转入 frozen)。可用余额 balance 小于预冻额时 MUST 拒绝调用,返回 POINTS_INSUFFICIENT 错误,不产生 usage 记录外的任何模型请求。

#### Scenario: 余额不足被拦截
- **WHEN** 用户 balance=2,发起预估需 50 积分的付费模型调用
- **THEN** 请求被拒绝,响应错误码 POINTS_INSUFFICIENT,账户余额与冻结无变化,模型未被调用

#### Scenario: 预冻结成功
- **WHEN** 用户 balance=200,发起预估 50 积分的调用
- **THEN** balance 变为 150、frozen 增加 50,模型调用放行

### Requirement: 调用后实结
调用完成后系统 SHALL 按实际 token 用量结算:实扣 = 折算积分,解冻预冻额,实扣大于预冻时补扣差额,实扣小于预冻时退回差额。补扣导致余额为负时 MUST 允许该笔记负并在账户标记欠费,后续充值优先抵扣。

#### Scenario: 实结少于预冻
- **WHEN** 预冻 50,实际折算 30
- **THEN** frozen 减 50,balance 净减 30,写一条 CONSUME 流水 30

#### Scenario: 实结多于预冻
- **WHEN** 预冻 50,实际折算 80,补扣时余额仅 10
- **THEN** frozen 清零,实扣 80,balance = -20 并标记欠费,流水反映全额

### Requirement: 调用失败全额解冻
模型调用失败(异常、超时、限流)时系统 SHALL 全额解冻预冻积分,usage 记录 status=FAILED,不产生 CONSUME 流水。

#### Scenario: 模型超时
- **WHEN** 预冻 50 后模型调用抛超时异常
- **THEN** frozen 减 50、balance 恢复,无消耗流水,usage status=FAILED

### Requirement: 免费模型兜底拦截
当用户余额(含欠费状态)不足以支付免费模型单次最低积分时,所有模型调用(含免费模型)MUST 被拦截,返回 POINTS_INSUFFICIENT,前端引导签到或充值。

#### Scenario: 欠费用户调免费模型
- **WHEN** 用户 balance=0 且处于欠费状态,发起免费模型调用
- **THEN** 请求被拦截,错误码 POINTS_INSUFFICIENT,提示签到或充值
