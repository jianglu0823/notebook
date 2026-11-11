# execution-contract.md — 会员积分账号体系(member-points)

基线:proposal.md + specs/(5 域 27 需求)+ design.md(D1-D9)+ tasks.md(6 批次 19 任务),DP-2 已于 2026-10-15 批准。

## 1. Intent Lock

为账户体系建立消耗型积分额度:所有 LLM 调用按 token 用量 × 模型单价折算积分扣减(付费模型真实单价,GLM 免费通道名义低费率防滥用),账户流水可追溯、日终清算对账,充值(兑换码/人工到账)与运营赠分(注册/签到/邀请/活动)、会员等级折扣构成商业闭环。小镇居民自主模拟归平台账户只计量不扣用户,用户主动互动归用户计费。

## 2. Scope Fence(禁止越界)

- 不接微信/支付宝在线支付(订单 channel 与 settlePaid 已预留,下迭代)
- 不做退款/提现/发票/积分转赠/企业共享账户
- 不引分布式锁、消息队列、ShedLock、xxl-job、API 网关
- 不改动既有 API 契约(仅新增 POINTS_INSUFFICIENT 错误码与积分端点)
- 不为积分体系重构既有模块结构;计量以装饰器收口,禁止散落调用点改造之外的架构变更

## 3. Approved Behavior 与 Test Obligations

每条需求对应单测(纯 Mockito 不起 Spring 上下文),场景即验收:

| 域 | 需求(压缩) | 测试义务 |
|---|---|---|
| account | 注册开户幂等;存量初始化;字段完整同事务;流水只增不改;并发条件更新不负余额;查询只限本人 | PointsAccountServiceTest + PointsControllerTest,6 场景全覆 |
| metering | 工厂统一收口;折算公式(最低 1 积分,免费≤付费 1/10);调用前预冻结;实结多退少补可透支记负;失败全额解冻;欠费全拦截 | PointsFacadeTest + MeteredChatModelTest,表驱动折算用例 |
| settlement | 日终对账出批次;差异明细+告警;管理端校准写 ADJUST;30min 冻结残留自动解冻;同日幂等 | SettlementServiceTest |
| recharge | 套餐管理;订单状态机 PENDING→PAID/CANCELLED;兑换码三态拒绝;人工到账三写同事务;到账幂等 | RechargeServiceTest |
| growth | 规则配置化即时生效;注册/签到/邀请/活动四类赠分各自防重;按累计充值分级,折扣实时参与折算 | GrowthServiceTest + LevelServiceTest |

## 4. Architecture & Dependency Constraints

- D1 计量收口:`ChatModelFactory` 返回 `MeteredChatModel` 装饰器,流结束自动 settle/release;`PointsFacade` 管 freeze/settle/release;禁止业务代码绕过工厂直接实例化模型
- D2 并发:数据库条件 UPDATE + `points_transaction` 唯一索引 `(user_id,type,biz_ref)`,DuplicateKeyException 转幂等返回
- D3 冻结:`points_account.frozen` 字段 + `points_usage` 状态机 PENDING→SETTLED/FAILED
- D4 清算:应用内 `@Scheduled(cron = "0 30 3 * * *")`,手动触发走管理端
- D5 透支:实结超预冻允许记负 + overdrawn 标记,充值先抵扣
- D6 免费模型:ModelPricing 加 freeChannel/名义费率,同一折算公式不特判
- D7 支付解耦:所有通道到账收敛 `RechargeService.settlePaid(orderNo, channel, operator)` 幂等
- D8 等级:points_level 配置表 + 缓存,充值到账即 recalc
- D9 小镇计费:居民自主模拟挂 SYSTEM_USER_ID 只计量;用户主动互动归用户
- 技术栈锁定:Spring Data JPA + Lombok;建表改 `infra/mysql/init.sql` 并对运行容器手工执行 DDL;新代码归属 `io.llmnote.points`

## 5. Execution Batches 与完成定义

| 批次 | 内容 | 完成定义(Done) |
|---|---|---|
| B1 账户基座(T1.1-T1.4) | 11 表 DDL+实体、AccountService、账户/流水接口、存量初始化 | 单测绿;容器内表已建;curl 账户/流水/初始化接口实测通过 |
| B2 计量扣费(T2.1-T2.4) | PointsFacade、定价扩展、装饰器收口、各入口接入 | 单测绿;冻结/实结/解冻/透支/欠费拦截场景全覆;既有 llm 包测试回归绿 |
| B3 充值(T3.1-T3.3) | 订单/兑换码/套餐、人工到账、幂等 | 单测绿;curl 套餐→下单→到账→余额流水实测通过 |
| B4 赠分等级(T4.1-T4.4) | 规则引擎、四类赠分、等级接通折算、注册钩子 | 单测绿;注册/签到/邀请 curl 实测通过;折扣进入折算公式 |
| B5 清算(T5.1-T5.2) | 对账/校准/残留扫描/定时调度 | 单测绿;手动触发批次记录生成;同日幂等实测 |
| B6 前端收尾(T6.1-T6.2) | App 积分页、网页积分区块、端到端冒烟 | flutter analyze 零问题;App/网页链路实测;全量测试绿;无既有契约破坏 |

依赖序:B1 → B2/B3 → B4 → B5 → B6(B4 依赖 B2 折算点,B5 依赖 B2 usage)。Review Timing:每批次完成后过 code-reviewer,review receipt pass 才开下一依赖批次。

## 6. Escalation Rules(回退条件)

出现以下任一情况,停止实施,回退到 specifying/bridging:

- 发现 specs 中 SHALL/MUST 无法在既定批次内满足(当前 27 条需求已全映射,无未映射项)
- 计量收口方案装饰器无法覆盖某调用形态(如发现不经 ChatModelFactory 的模型实例化点)
- 冻结/实结模型在流式中断场景出现账实不符且 D3 无法修补
- 需要引入 Scope Fence 禁止项(在线支付、分布式锁、MQ 等)
- 既有 API 契约被迫破坏

## 7. Approval Gate

DP-3 批准后进入 approved-for-build;build-executor 先做 execution plan 再动手。本契约 hash 变化或上述基线产物实质变更时,契约视为过期,需重新桥接。
