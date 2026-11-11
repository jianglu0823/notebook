# design.md — 会员积分账号体系

## Context

现状:Spring Boot 3 单体(notebooklm-app),MySQL `notebooklm`,Spring Data JPA(`ddl-auto: none`,建表走 `infra/mysql/init.sql`),单表查询内存组装,无 JOIN 惯例。LLM 调用全部经 `ChatModelFactory` 产出模型实例、经 `ChatCompletion` 阻塞调用,流式场景(QA SSE、伴侣 WS、小镇居民)直接拿工厂模型实例消费流。模型单价已在 `NotebookLmProperties.ModelPricing`(inputPer1k/outputPer1k 元/千 token)配置,`ChatCompletion.Result` 已返回 input/output token。无任何计费/账户模块。GLM 走免费通道,Qwen/DashScope 付费。

约束:不引分布式锁、不上消息队列(够用原则);积分变动必须强一致(钱的事);计量对调用链路的性能损耗要可忽略;改造不能破坏现有 API 契约。

干系人:用户(余额可见、扣费明白)、运营(套餐/规则/赠分/对账)、平台(成本可控)。

## Goals

- 每次 LLM 调用都有账:谁、哪个功能、哪个模型、多少 token、多少积分。
- 余额强一致:并发不负余额(授权透支除外),流水只增不改,日终可对账。
- 商业化闭环:充值(兑换码/人工)→ 消耗 → 赠分 → 等级折扣,规则全配置化。
- 支付通道预留:订单与到账逻辑与通道解耦,未来接微信/支付宝只加回调适配。

## Decisions

### D1 计量收口:装饰器包住 ChatModelFactory 产物 + Facade 管冻结实结

**Choice**:新增 `PointsFacade`(freeze/settle/release/欠费检查)。`ChatModelFactory` 返回的模型实例包一层 `MeteredChatModel` 装饰器:流消费完(或阻塞返回)时拿到累计 usage,调 `PointsFacade.settle` 实结;异常路径调 `release` 解冻。业务入口(controller/service 起调用处)先调 `freeze(userId, feature, 预估上限)` 拿 bizRef,贯穿到 settle。

**Rationale**:工厂是所有调用的必经之地,装饰器在此收口则 20+ 调用点零侵入获得计量;冻结需要业务上下文(功能、预估上限),放入口处显式调用。

**Alternatives**:①AOP 注解拦截各 Service——调用点分散在 WS handler、SSE、定时任务,切面表达式维护脆;②HTTP 层按 token 估算——拿不到真实 usage;③API 网关计费——无网关,引入是过度设计。

### D2 并发一致性:数据库条件更新,不上锁

**Choice**:扣减/冻结用条件 UPDATE(`balance >= ?` 或 `version = ?` 乐观锁),更新行数为 0 即失败回滚。流水表 `(user_id, type, biz_ref)` 唯一索引做天然幂等键,重复入账直接主键冲突兜底。

**Rationale**:单行原子更新在 MySQL InnoDB 下足够;用户级并发量低,Redis 锁/分布式锁是负担。

**Alternatives**:Redis 分布式锁(多组件多故障点)、队列串行化(延迟与复杂度不值)。

### D3 预冻结模型:account.frozen 单字段 + usage 状态机

**Choice**:账户加 `frozen` 字段,freeze 时 `balance-=x, frozen+=x`;usage 记录 status: PENDING → SETTLED / FAILED。settle 时 `frozen-=冻结额, balance-=(实扣-冻结额)`。清算扫描 PENDING 超 30 分钟的 usage 自动 release。

**Rationale**:一个账户同时进行的调用极少(单用户场景),单字段够用;usage 记录本身就是冻结凭据,不另建冻结表。

**Alternatives**:独立 freeze 明细表(字段冗余,查询多一步);Redis 冻结(宕机丢账,不能接受)。

### D4 清算调度:应用内 @Scheduled,不接 schedule-agent

**Choice**:notebooklm-app 内 `@Scheduled(cron = "0 30 3 * * *")` 跑日终对账,管理端提供手动触发接口。批次记录 + 账户日快照两张表。

**Rationale**:清算是账本内部事务,跟业务日程无关;schedule-agent 是用户日程服务,跨服务调它做账是错误归属。

**Alternatives**:schedule-agent A2A 触发(错误归属)、xxl-job(为一个任务引平台不值)。

### D5 欠费与透支:授权透支记负,充值优先抵扣

**Choice**:实结超预冻允许 balance 记负并置 `overdrawn` 标记;欠费用户冻结检查按 `balance > 免费模型单次最低积分` 判定,充值到账先冲抵负值。

**Rationale**:流式调用的真实用量事前不可知,硬掐断体验差;小额透支+兜底拦截是体验与风控的平衡。

**Alternatives**:预估即硬上限、超出断流(回答说一半,体验差);无限透支(成本失控)。

### D6 免费模型计费:名义费率配置在 ModelPricing

**Choice**:`ModelPricing` 加 `freeChannel` 与名义单价(默认 0.0001 元/千 token,即最低付费模型的 1/10 量级),折算走同一公式,不特判。

**Rationale**:规则统一,费率调整只改配置;签到赠分(默认 10 积分 ≈ 免费模型数百次调用)可覆盖基本使用,与"防滥用不防正常用"的目标一致。

**Alternatives**:免费模型每日限额次数(另一套规则引擎,复杂);完全免费(用户拍板要扣)。

### D7 充值通道解耦:订单 channel 字段 + 统一到账款

**Choice**:`points_order.channel`(REDEEM/MANUAL/ONLINE),所有通道到账都收敛到 `RechargeService.settlePaid(orderNo)` 幂等到账。未来在线支付只需加回调 Controller 验签后调同一方法。

**Alternatives**:首期直接微信支付(商户号、证书、回调公网暴露,首期成本过高)。

### D8 等级实时生效:配置表 + 内存缓存

**Choice**:`points_level` 表(等级/充值阈值/折扣率/赠倍率),应用内缓存,管理端改配置后失效。充值到账事务内重算用户等级,计量折算时读缓存取折扣。

**Alternatives**:等级写死枚举(运营调不了);按累计消耗分级(鼓励白嫖,方向错)。

### D9 小镇居民自主模拟归平台账户,只计量不扣用户

**Choice**:world 模块居民自主行为(闲逛/聊天/评论/读书)的 LLM 调用,计量记录挂在平台账户(SYSTEM_USER_ID)下,不扣任何用户积分;用户主动发起的互动(靠近对话、派任务)归该用户正常计费。

**Rationale**:居民自主模拟是系统驱动行为,扣用户会让用户为不可控行为欠费;但用量必须留账,平台才能观测居民模拟的真实成本。

**Alternatives**:全部归属主用户扣费(用户莫名欠费,体验不可接受);居民调用完全不计量(成本黑洞)。

## 数据模型(新表 11 张,均单表无 JOIN)

| 表 | 关键字段 | 说明 |
|---|---|---|
| points_account | user_id UK, balance, frozen, total_recharged, total_consumed, level, overdrawn, version | 账户,乐观锁 |
| points_transaction | user_id, type, amount, balance_after, biz_ref, remark;UK(user_id,type,biz_ref) | 流水,只增不改 |
| points_usage | user_id, model, feature, input_tokens, output_tokens, points, status, biz_ref, settled_at | 计量记录/冻结凭据 |
| points_package | name, points, price_fen, bonus_rate, status, sort | 充值套餐 |
| points_order | order_no UK, user_id, package_id, points, amount, channel, status, paid_at | 充值订单 |
| points_redeem_code | code UK, package_id, status, used_by, used_at, expires_at, batch_no | 兑换码 |
| points_rule | type UK, amount, enabled, daily_cap, start_at, end_at | 运营赠分规则 |
| points_level | level UK, threshold, discount, bonus_rate | 等级配置 |
| points_checkin | UK(user_id, checkin_date), streak | 签到记录 |
| points_settlement_batch | settle_date UK, account_count, tx_count, diff_count, diff_total, detail(JSON) | 清算批次 |
| points_snapshot | UK(user_id, snapshot_date), balance | 账户日快照 |

## Risks And Trade-Offs

- **调用点绕过计量**:业务直接 new 模型或不走工厂就无账。缓解:装饰器在工厂内收口 + 代码评审红线 + 清算用 usage 与日志对账抽查。
- **流式中断的用量归属**:连接中断时已产出 token 是真实成本。决策:流中拿到过累计 usage 就按已产出实结,完全无 usage 才全额解冻——宁可平台小亏,不可多扣用户。
- **冻结残留**:进程崩溃导致 PENDING 永远不实结。缓解:清算 30 分钟超时自动解冻(已是 spec)。
- **性能**:每次调用多 2-3 次单行 DB 写(freeze/settle/流水)。单用户并发低,可接受;若成为瓶颈再引入批量异步写,但会牺牲实时余额——不预先优化。
- **存量初始化**:一次性任务在大用户量下分批跑,幂等可重入(已是 spec)。
- **负余额 UX**:欠费后免费模型也被拦,用户可能困惑。缓解:错误码带引导文案,前端积分页显著展示欠费状态与签到入口。
