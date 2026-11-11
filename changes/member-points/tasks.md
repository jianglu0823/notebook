# tasks.md — 会员积分账号体系实施批次

约定:技术栈 Spring Data JPA + Lombok + JUnit5/Mockito/AssertJ(纯单测不起 Spring 上下文);建表走 `infra/mysql/init.sql` 并需对运行中容器手工执行 DDL;回归测试不真调 LLM。

## File Structure

### Create

| 文件 | 职责 |
|---|---|
| `notebooklm-app/src/main/java/io/llmnote/points/PointsAccount.java` | 账户实体(余额/冻结/累计/等级/乐观锁) |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsTransaction.java` | 流水实体(只增不改,唯一幂等键) |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsUsage.java` | 计量记录实体(冻结凭据 + 状态机) |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsPackage.java` | 充值套餐实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsOrder.java` | 充值订单实体(状态机) |
| `notebooklm-app/src/main/java/io/llmnote/points/RedeemCode.java` | 兑换码实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsRule.java` | 运营赠分规则实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsLevel.java` | 等级配置实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsCheckin.java` | 签到记录实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/SettlementBatch.java` | 清算批次实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsSnapshot.java` | 账户日快照实体 |
| `notebooklm-app/src/main/java/io/llmnote/points/TxType.java` | 流水类型枚举(RECHARGE/CONSUME/SIGNUP/CHECKIN/INVITE/ACTIVITY/ADJUST/INIT/FREEZE/UNFREEZE) |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsInsufficientException.java` | 余额不足异常,映射 POINTS_INSUFFICIENT 错误码 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsRepository.java` 等 11 个 Repository | 各实体 JpaRepository |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsAccountService.java` | 开户/查询/条件更新入账扣减原语 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsFacade.java` | 计量门面:freeze/settle/release/欠费判定/折算 |
| `notebooklm-app/src/main/java/io/llmnote/points/MeteredChatModel.java` | ChatModelBase 装饰器,流结束自动 settle/release |
| `notebooklm-app/src/main/java/io/llmnote/points/RechargeService.java` | 下单/兑换码/人工到账/幂等 |
| `notebooklm-app/src/main/java/io/llmnote/points/GrowthService.java` | 赠分规则/注册/签到/邀请/活动 |
| `notebooklm-app/src/main/java/io/llmnote/points/LevelService.java` | 等级配置缓存/折扣查询/升级重算 |
| `notebooklm-app/src/main/java/io/llmnote/points/SettlementService.java` | 日终对账/校准/残留扫描/幂等 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsController.java` | 用户端:账户/流水/套餐/下单/兑换码/签到 |
| `notebooklm-app/src/main/java/io/llmnote/points/PointsAdminController.java` | 管理端:套餐/兑换码/人工到账/活动赠分/校准/初始化/清算触发 |
| `notebooklm-app/src/test/java/io/llmnote/points/` 下 6 个测试类 | 对应 Service/Facade 单测 |
| `app_flutter/lib/pages/me/points_page.dart` | App 积分页(余额/流水/套餐/签到) |

### Modify

| 文件 | 改动 |
|---|---|
| `infra/mysql/init.sql` | 新增 11 张表 DDL |
| `notebooklm-app/src/main/java/io/llmnote/config/NotebookLmProperties.java` | ModelPricing 加 freeChannel 与名义单价 |
| `notebooklm-app/src/main/java/io/llmnote/llm/ChatModelFactory.java` | forModel 返回 MeteredChatModel 包装 |
| `notebooklm-app/src/main/java/io/llmnote/llm/ChatCompletion.java` | completeWithUsage 接入 freeze/settle |
| `notebooklm-app/src/main/java/io/llmnote/auth/AuthController.java` | register 成功钩子:开户+注册赠分 |
| `notebooklm-app/src/main/java/io/llmnote/auth/User.java` | 加 inviteCode 字段 |
| `notebooklm-app/src/main/java/io/llmnote/qa/QaController.java` | SSE 入口 freeze,结束 settle |
| `notebooklm-app/src/main/java/io/llmnote/companion/`(WS handler 与 Service) | 聊天入口 freeze/settle |
| `notebooklm-app/src/main/java/io/llmnote/world/`(AgentChatService 等) | 用户主动互动归用户计费;居民自主模拟归平台账户只计量 |
| `notebooklm-app/src/main/java/io/llmnote/studio/`、`gen/`、`voice/` 入口 | freeze/settle 接入 |
| `notebooklm-app/src/main/java/io/llmnote/auth/ApiExceptionHandler.java` | PointsInsufficientException → POINTS_INSUFFICIENT |
| `notebooklm-app/src/main/resources/static/index.html` | 我的面板加积分区块 |
| `app_flutter/lib/pages/me/me_tab.dart` | 加积分页入口 |

## Interfaces

批次间契约(精确类型):

```java
// Batch 1 产出,Batch 2/3/4/5 消费
public interface PointsAccountService {
    PointsAccount openAccount(Long userId);                       // 幂等
    PointsAccount getAccount(Long userId);                        // 无账户抛 404
    Page<PointsTransaction> pageTransactions(Long userId, TxType type, int page, int size);
    // amount>0 入账 / amount<0 扣减;扣减余额不足抛 PointsInsufficientException;
    // (userId,type,bizRef) 唯一索引冲突视为重复请求,返回已存在流水(幂等)
    PointsTransaction applyTransaction(Long userId, TxType type, long amount, String bizRef, String remark);
    boolean freezeAtomic(Long userId, long points);               // 条件更新 balance-=p,frozen+=p;失败 false
    void settleAtomic(Long userId, long frozen, long actual);     // frozen-=frozen,balance-=(actual-frozen),允许负
    void unfreezeAtomic(Long userId, long points);                // frozen-=p,balance+=p
}

// Batch 2 产出,Batch 4 消费(折扣),各 LLM 入口消费
public interface PointsFacade {
    String freeze(Long userId, String feature, long estimatePoints);  // 返回 bizRef;不足抛 PointsInsufficientException
    void settle(String bizRef, long inputTokens, long outputTokens);  // 按定价折算实结
    void release(String bizRef);                                       // 失败全额解冻
    boolean isOverdrawnBlocked(Long userId);                           // 欠费兜底:免费模型也拦
}

// Batch 3 产出,未来支付通道消费
public interface RechargeService {
    PointsOrder createOrder(Long userId, Long packageId);
    PointsOrder redeem(Long userId, String code);                      // 兑换码直达 PAID
    PointsOrder settlePaid(String orderNo, String channel, String operator); // 幂等到账
}

// Batch 4 产出,Batch 2 折算消费
public interface LevelService {
    BigDecimal discountRate(Long userId);   // 当前等级消耗折扣,缓存
    void recalcLevel(Long userId);          // 充值到账后调用
}
```

## Batch 1:账户流水基座

### Task 1.1 建表与实体骨架
Depends on: 无

1. 在 `infra/mysql/init.sql` 追加 11 张表 DDL(按 design.md 数据模型,`points_transaction` 建 `uk_user_type_bizref` 唯一索引,`points_account.user_id` 唯一)
2. 对运行容器执行 DDL:`docker exec -i nblm-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" notebooklm < 新增段`,`SHOW TABLES` 实测验证
3. 写 11 个实体类 + TxType 枚举 + 11 个 JpaRepository(参照 AccessLog 风格)
4. `./mvnw -f notebooklm-app compile` 编译通过
5. 启动应用 `APP_PORT=8082`,JPA 映射无报错,`curl /api/notes` 回归 200

Interfaces: Produces 全部实体/Repository(Batch 1-5 消费)

### Task 1.2 PointsAccountService 条件更新与幂等
Depends on: Task 1.1

1. 写失败测试 `PointsAccountServiceTest`:开户幂等、applyTransaction 入账/扣减/余额不足抛异常、唯一索引冲突返回已有流水、freezeAtomic 余额不足返回 false
2. 实现 `PointsAccountService`:applyTransaction 用条件 UPDATE(balance>=?),流水插入捕获 DuplicateKeyException 转幂等返回
3. 测试全绿
4. 重构:扣减/入账共用私有方法收敛
5. 全量单测回归 `./mvnw -f notebooklm-app test -Dtest='PointsAccountServiceTest'`

Interfaces: Produces `PointsAccountService`(签名见 Interfaces 节)

### Task 1.3 用户端账户/流水接口
Depends on: Task 1.2

1. 写失败测试 `PointsControllerTest`:GET /api/points 返回本人账户、GET /api/points/transactions 分页+type 过滤、无账户返回 404
2. 实现 `PointsController`(Principal 注入,只取本人数据)
3. `ApiExceptionHandler` 加 PointsInsufficientException → HTTP 402 + `{"code":"POINTS_INSUFFICIENT"}` 映射
4. 测试全绿
5. 启动 curl 实测两接口 200/402 路径

Interfaces: Produces `GET /api/points`、`GET /api/points/transactions`(Batch 6 前端消费)

### Task 1.4 存量用户初始化
Depends on: Task 1.2

1. 写失败测试:无账户用户开户并写 INIT 流水、已有账户跳过、重复执行幂等
2. 实现 `PointsAdminController.initExisting()` + Service 分批(每批 200)处理
3. 测试全绿
4. 管理端接口加管理员鉴权(对齐现有 admin 端点做法)
5. 启动后对测试库手动触发一次,抽查 3 个存量用户账户与 INIT 流水

Interfaces: Produces `POST /api/admin/points/init`

## Batch 2:计量扣费

### Task 2.1 PointsFacade 冻结/实结/解冻核心
Depends on: Task 1.2

1. 写失败测试 `PointsFacadeTest`:freeze 足额成功/不足抛异常、settle 实结多退少补、补扣透支记负+overdrawn、release 全额解冻、重复 settle 幂等(bizRef 唯一)
2. 实现 `PointsFacade`(组合 PointsAccountService + PointsUsageRepository),折算公式按 design D6,等级折扣先留 `LevelService` 接口注入(Batch 4 实现,此处默认 1.0 桩)
3. 测试全绿
4. 重构:折算函数提取为纯静态方法便于表驱动测试
5. 单测回归

Interfaces: Consumes `PointsAccountService`;Produces `PointsFacade`

### Task 2.2 定价配置扩展
Depends on: 无

1. 写失败测试:freeChannel=true 模型按名义单价折算、默认不超过最低付费模型 1/10
2. `NotebookLmProperties.ModelPricing` 加 `freeChannel`/`nominalPrice` 字段,application.yml 给 GLM 模型配名义费率
3. `PricingController` 输出带上 freeChannel 标记
4. 测试全绿
5. curl /api/pricing 实测返回新字段

Interfaces: Produces 扩展后的 `ModelPricing`(Task 2.1 消费)

### Task 2.3 MeteredChatModel 与工厂收口
Depends on: Task 2.1

1. 写失败测试 `MeteredChatModelTest`:流正常结束触发 settle(累计 usage 透传)、流中异常触发 release、无 usage 时 release
2. 实现 `MeteredChatModel extends ChatModelBase` 委托包装,`ChatModelFactory.forModel` 返回包装实例
3. 测试全绿
4. 工厂内保证 bizRef 经 ThreadLocal/显式参数传递,避免串请求
5. 编译 + 关联单测回归(llm 包既有测试)

Interfaces: Consumes `PointsFacade`;Produces 收口后的 `ChatModelFactory`

### Task 2.4 阻塞与流式入口接入
Depends on: Task 2.3

1. 写失败测试:ChatCompletion.completeWithUsage 先 freeze 后 settle、异常 release;QaController SSE 入口 freeze、done 事件 settle、断开 release
2. `ChatCompletion` 接入(阻塞式一处收口);`QaController`、伴侣 WS handler 入口接入
3. 测试全绿(mock facade)
4. world/studio/gen/voice 入口接入;world 居民自主模拟传平台账户 SYSTEM_USER_ID 只计量不扣用户(读配置开关,默认开)
5. 全量单测回归 + 启动冒烟(不真调 LLM,curl 验证入口无 500)

Interfaces: Consumes `PointsFacade`

## Batch 3:充值

### Task 3.1 RechargeService 订单与到账幂等
Depends on: Task 1.2

1. 写失败测试 `RechargeServiceTest`:下单含赠送计算、下架套餐拒绝、状态机非法迁移拒绝、settlePaid 重复调用幂等、到账三写同事务
2. 实现 `RechargeService`(到账后调 `LevelService.recalcLevel`,Batch 4 前用桩)
3. 测试全绿
4. 重构:到账收敛单入口 settlePaid
5. 单测回归

Interfaces: Consumes `PointsAccountService`;Produces `RechargeService`

### Task 3.2 兑换码
Depends on: Task 3.1

1. 写失败测试:有效码直达 PAID 到账、无效/已用/过期拒绝无流水、管理端批量生成码唯一
2. 实现 redeem + `PointsAdminController` 批量生成(每批带 batchNo)
3. 测试全绿
4. 码值生成用 `SecureRandom` 12 位字母数字,DB 唯一索引兜底
5. 启动 curl 实测生成→兑换全链路

Interfaces: Produces `POST /api/points/redeem`、`POST /api/admin/points/redeem-codes`

### Task 3.3 套餐与用户端充值接口
Depends on: Task 3.1

1. 写失败测试 `PointsControllerTest` 增补:套餐列表只返回上架、下单成功、管理端套餐 CRUD
2. 实现套餐查询/下单用户端点 + 管理端套餐 CRUD + 人工到账端点
3. 测试全绿
4. 管理端端点统一鉴权检查
5. curl 实测套餐→下单→人工到账→余额与流水

Interfaces: Produces `GET /api/points/packages`、`POST /api/points/orders`、`POST /api/admin/points/orders/{orderNo}/paid`

## Batch 4:运营赠分与等级

### Task 4.1 GrowthService 规则引擎与四类赠分
Depends on: Task 1.2

1. 写失败测试 `GrowthServiceTest`:规则开关/额度读取、注册赠分只发一次、签到日唯一+streak、邀请双方赠分+自邀请拒绝、活动赠分全员批处理幂等
2. 实现 `GrowthService` + `PointsRule` 缓存(管理端改后失效)
3. 测试全绿
4. 每日全站发放上限 daily_cap 用流水按日统计校验
5. 单测回归

Interfaces: Consumes `PointsAccountService`;Produces `GrowthService`

### Task 4.2 LevelService 与计量折扣接通
Depends on: Task 4.1, Task 2.1

1. 写失败测试 `LevelServiceTest`:阈值分级、充值到账触发 recalc 升級、discountRate 缓存、折扣参与折算(PointsFacade 集成测)
2. 实现 `LevelService`,替换 Task 2.1/3.1 中的桩
3. 测试全绿
4. 等级配置变更缓存失效(管理端写 points_level 后清缓存)
5. 单测回归

Interfaces: Produces `LevelService`;接通 PointsFacade 折算与 RechargeService 到账钩子

### Task 4.3 注册钩子与邀请码
Depends on: Task 4.1

1. 写失败测试:注册成功自动开户+SIGNUP 流水、填邀请码双方 INVITE 流水
2. `AuthController.register` 成功路径接入(事务外后置,失败不阻断注册,记错误日志)
3. `User` 加 inviteCode,注册时生成唯一码
4. 测试全绿
5. curl 实测注册→账户/流水/邀请码

Interfaces: Consumes `PointsAccountService.openAccount`、`GrowthService`

### Task 4.4 签到与活动赠分端点
Depends on: Task 4.1

1. 写失败测试:签到接口日唯一、重复拒绝、管理端活动赠分单发/全员
2. `PointsController` 加 POST /api/points/checkin;`PointsAdminController` 加活动赠分端点
3. 测试全绿
4. 全员赠分批处理 + 断点续跑(按 user_id 游标)
5. curl 实测签到/活动赠分

Interfaces: Produces `POST /api/points/checkin`、`POST /api/admin/points/activity-grant`

## Batch 5:清算

### Task 5.1 SettlementService 对账与校准
Depends on: Task 1.2, Task 2.1

1. 写失败测试 `SettlementServiceTest`:无差异批次生成、差异检测+明细、管理端校准写 ADJUST 流水、同日重复执行幂等
2. 实现 `SettlementService`(快照+流水合计 vs balance;批次表按 settle_date 唯一)
3. 测试全绿
4. 校准接口鉴权+操作人/原因强制填写
5. 单测回归

Interfaces: Consumes `PointsAccountService`、流水/快照 Repository;Produces `POST /api/admin/points/settlement/run`、`POST /api/admin/points/settlement/adjust`

### Task 5.2 冻结残留扫描与定时调度
Depends on: Task 5.1

1. 写失败测试:PENDING 超 30 分钟 usage 自动 release、未超时不动、重复扫描幂等
2. 实现残留扫描并入清算流程;`@Scheduled(cron = "0 30 3 * * *")` 触发,开关注入
3. 测试全绿
4. 调度用 ShedLock?否——单体单实例,直接 @Scheduled(遵循够用原则)
5. 启动后手动触发接口实测批次记录生成

Interfaces: Produces 日终清算自动任务

## Batch 6:前端与收尾

### Task 6.1 App 积分页
Depends on: Task 1.3, 3.3, 4.4

1. 写 `app_flutter/lib/pages/me/points_page.dart`:余额卡(余额/冻结/等级)、流水列表(分页)、套餐列表+下单、兑换码输入、签到按钮
2. `me_tab.dart` 加入口;POINTS_INSUFFICIENT 错误全局捕获跳积分页
3. `flutter analyze` 零问题
4. debug 构建装模拟器,实测余额/流水/签到/兑换码链路
5. 释放冻结与欠费提示 UI 走查

Interfaces: Consumes Batch 1/3/4 全部用户端接口

### Task 6.2 网页积分区块与端到端冒烟
Depends on: Task 6.1

1. `index.html` 我的面板加积分区块(余额/流水/签到/套餐),复用现有 fetch 封装
2. POINTS_INSUFFICIENT 时弹充值引导
3. 浏览器实测网页链路
4. 端到端冒烟:注册→开户赠分→签到→兑换码充值→余额/流水/等级全链路(curl + 浏览器,不真调 LLM)
5. 全量单测 + 既有回归测试全绿,确认无既有 API 契约破坏

Interfaces: 全部产出最终验收

## 需求映射

| 需求 | 任务 |
|---|---|
| account:注册开户/幂等 | 1.2, 4.3 |
| account:存量初始化 | 1.4 |
| account:字段完整/流水只增/并发一致 | 1.1, 1.2 |
| account:查询接口 | 1.3 |
| metering:统一收口/usage 记录 | 2.3, 2.4 |
| metering:折算/免费低费率 | 2.1, 2.2 |
| metering:预冻结/实结/失败解冻/欠费拦截 | 2.1, 2.4 |
| settlement:对账/校准/残留/幂等 | 5.1, 5.2 |
| recharge:套餐/订单/兑换码/人工到账/幂等 | 3.1, 3.2, 3.3 |
| growth:规则/注册/签到/邀请/活动/等级 | 4.1, 4.2, 4.3, 4.4 |
