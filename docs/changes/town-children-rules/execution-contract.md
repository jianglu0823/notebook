# Execution Contract: town-children-rules

## Workflow
- **Mode**: full
- **Execution mode**: sdd (default for full)
- **Plan revision**: 1 (pending — recorded by `ssf execution plan`)

## Intent Lock

- **变更名称**: town-children-rules
- **要解决的问题**: 小镇居民生子 avatar 硬编码 `👶`(视觉单一)+ 出生无上限(8%/天 × 30 天,长期越积越多)。
- **范围内**:
  1. 孩子 avatar 走 9 emoji 池(可配 `world.children.avatar-pool`)。
  2. 已婚夫妇达到 `world.children.max-per-couple`(默认 3)个孩子后,`dailySettlement()` 不再让该夫妇怀上。
  3. 启动时 `CommandLineRunner`(`MigrateChildDataRunner`)把历史孩子的 avatar 重抽一次,清掉超额夫妇的 `pregnantSince` / `dueDate`。
  4. 上述两配置 key 在 `application.yml` 暴露,代码内置默认值。
- **范围外**:
  - 引入 `sex` / `gender` 字段(避免 schema 变更,改用纯池随机)。
  - 修改 `AgentEmployee` 实体结构 / `parent_ids` 列类型 / DB migration。
  - 调整 8% 日受孕率 / 30 天孕期 / `uniqueChildName()` 重名逻辑。
  - 新增公开 API 或 admin 端点手动触发迁移。
  - 调整 `moodEmoji` / `color` / `visualDescriptor` 等其他视觉字段。
  - 离婚 / 死亡 / 婚礼机制(只动出生)。

## Approved Behavior

| Req | 摘要 | 关键 scenario |
|---|---|---|
| R-1 | 新生儿 avatar 从池中确定性抽取 | 默认池 / 自定义池 / 空池降级 / 同父母多子有变化 |
| R-2 | 夫妇 cap 后不再怀上,8% 与 30 天不变 | 已达 cap / 不足 cap / 单亲 / cap=1 |
| R-3 | 配置走 `application.yml`,缺省回退 | 无 key → 默认 / 自定义 → 覆盖 |
| R-4 | 启动时迁移,幂等 + 单行失败不挂 | 空表 / 混合 / 幂等 / 部分失败 |

完整 WHEN/THEN 见 `specs/children-rules/spec.md`。

## Design Constraints

- **架构约束**:
  - `WorldChildrenProperties`(`io.llmnote.world.config` 包)用 `@ConfigurationProperties("world.children")` 绑定,默认在字段初始化器里给。
  - `MigrateChildDataRunner` 实现 `CommandLineRunner`,在 EMF 准备好之后、HTTP 监听器启动之前跑一次。
  - 不引入新模块,所有改动在 `io.llmnote.world.*` 包内。
- **接口约束**:
  - `birth()` 签名不变,内部把传入的 `avatar` 参数忽略,改用 `props.pickAvatar(name)`。
  - `childbirths()` 签名不变,内部在 8% 判定前加 cap 预检。
  - `MigrateChildDataRunner.run()` 标准 `CommandLineRunner` 入口,无返回值。
- **依赖约束**:
  - 仅依赖 Spring Boot starter + Lombok + Spring Data JPA(均已存在)。
  - 不引入新 Maven 依赖。
- **数据约束**:
  - `parent_ids` 是 `VARCHAR(64)` 逗号分隔,intersect 逻辑用 in-app split + 集合比较;无 schema 变更。
  - 迁移是事务级 `save()`,单行 try/catch 隔离失败;启动不被阻塞。

## Execution Plan

默认 SDD,3 wave 串行(W1 → W2 → W3)。每 wave 完成后写 review report + 录 `ssf execution review ... --verdict pass` 才进下一 wave。

### Wave 1 — Foundation (T1, T2, T3)

- **Wave ID**: W1
- **任务**: T1, T2, T3
- **依赖 wave**: 无
- **策略**: `serial`(T1 → T2 → T3;实际上 T1/T2 可并行,这里走 serial 简化)
- **目标**: 建出 `WorldChildrenProperties` POJO + `findByParentIdsIsNotNull()` repo 方法 + `application.yml` 配置骨架。无运行时行为变更。
- **输入**: 当前 `AgentEmployee` / `AgentEmployeeService` / `application.yml`。
- **输出**:
  - `notebooklm-app/src/main/java/io/llmnote/world/config/WorldChildrenProperties.java`(新)
  - `notebooklm-app/src/main/java/io/llmnote/world/AgentEmployeeRepository.java` +1 方法
  - `notebooklm-app/src/main/resources/application.yml` 加 `world.children` 段(注释形式)
  - `notebooklm-app/src/test/java/io/llmnote/world/config/WorldChildrenPropertiesTest.java`(新,3 case)
- **完成标准**:
  - 3 个 task 的 TDD 阶段全过(RED 写过、GREEN 落代码、REFACTOR 清过)
  - 既有 194 个测试 0 回归
  - `./mvnw -pl notebooklm-app test -Dtest=WorldChildrenPropertiesTest` 3 个 case 全绿
- **Review gate**: `docs/changes/town-children-rules/.superpowers/sdd/reviews/W1.md`,base=base-sha,head=W1 commit SHA,receipt=`pass`

### Wave 2 — Apply to new births (T4, T5, T6)

- **Wave ID**: W2
- **依赖 wave**: W1
- **策略**: `serial`(T4 → T5 → T6)
- **目标**: 把"池 + cap"接进实际出生路径。`birth()` 用 `pickAvatar`;`childbirths()` 在 8% 前查该夫妇现有孩子数,过 cap 则 `continue`。
- **输入**: W1 产出的 `WorldChildrenProperties`。
- **输出**:
  - `AgentEmployeeService.birth()` 改用 `props.pickAvatar(name)`
  - `WorldSimEngine.childbirths()` 新增 `coupleAtCap(a, b, byId)` 预检
  - `notebooklm-app/src/test/java/io/llmnote/world/WorldSimEngineChildbirthThrottleTest.java`(新,4 case:过 cap / 不足 cap / cap=1 / e2e)
- **完成标准**:
  - 4 个 case 全绿
  - 既有 194 个测试 0 回归
  - `dailySettlement()` 行为对未达 cap 夫妇完全等价于改动前(同 8%、同 30 天、同 persona 字符串)
- **Review gate**: `docs/changes/town-children-rules/.superpowers/sdd/reviews/W2.md`,base=W1 head,head=W2 commit SHA,receipt=`pass`

### Wave 3 — Historical migration (T7, T8, T9)

- **Wave ID**: W3
- **依赖 wave**: W1, W2
- **策略**: `serial`(T7 → T8 → T9)
- **目标**: 启动时一次性迁移历史数据,所有 child avatar 重抽 + 超 cap 夫妇清孕期字段 + 单行失败不挂。
- **输入**: W2 已就位的 `birth()` / `childbirths()` 行为(但不影响迁移本身,只是确保 schema 行为一致)。
- **输出**:
  - `notebooklm-app/src/main/java/io/llmnote/world/MigrateChildDataRunner.java`(新,带 Javadoc)
  - `notebooklm-app/src/test/java/io/llmnote/world/MigrateChildDataRunnerTest.java`(新,4 case:空表 / 混合 / 幂等 / 部分失败)
- **完成标准**:
  - 4 个 case 全绿
  - 既有 194 + W1/W2 新增测试 0 回归
  - 在已有数据的 H2 集成测试环境(如有)启动一次,验证 `INFO` 日志输出 `childrenReavatar=N couplesCleared=M`
- **Review gate**: `docs/changes/town-children-rules/.superpowers/sdd/reviews/W3.md`,base=W2 head,head=W3 commit SHA,receipt=`pass`

## Test Obligations

- **必须先从失败测试开始的行为**:
  - T1: `WorldChildrenProperties` 默认值 + `pickAvatar` 确定性
  - T4-T5: 夫妇 cap 不怀上 / 不足 cap 怀上
  - T7-T8: 迁移空表 / 混合 / 幂等 / 部分失败
- **必需的边界情况**:
  - 头像池为空 → 回退 `👶`(R-1)
  - 夫妇 cap=1 立即生效(R-2)
  - 单亲(`parentIds = "a,a"`)被 cap 正确计数(R-2)
  - 迁移第二次跑不改任何数据(R-4)
  - 迁移中一行 `save()` 抛异常时应用仍启动(R-4)
- **回归敏感区域**:
  - `WorldSimEngine.dailySettlement()` 主体(wedding / death / economic 段落不要受影响)
  - `AgentEmployeeService.create()`(非 `birth()` 的管理员创建路径)不要被波及
  - 论坛 / 创作 / 群聊 / 笔记等模块无任何改动

## Execution Mode

- **模式**: `sdd`(full 默认)
- **选择理由**: 跨 3 个文件类型(POJO / service / runner),9 个 task 跨 3 批次,带数据迁移。inline / batch-inline 不适用(用户也未 override)。
- **用户 explicit override**: 无
- **执行计划命令**:
  ```
  ssf execution plan <change-dir> --mode sdd \
    --reason "town-children-rules full SDD; 3 waves 串行;foundation → apply → migration" \
    --wave W1:serial:1,2,3 \
    --wave W2:serial:4,5,6:W1 \
    --wave W3:serial:7,8,9:W1,W2
  ```
- **允许的修订**: `ssf execution revise` 创建新 revision(清除旧 receipt);不允许降级。
- **计划 revision / artifact hash**: 由 `ssf execution plan` 写入后填入。

## Verification Dimensions

| 维度 | 状态 | 发现 |
|------|------|------|
| Completeness | Pending | — |
| Correctness | Pending | — |
| Coherence | Pending | — |
| Unintended Scope | Pending | — |

**总体结论**: Pending(W3 review pass 后填)

## Review Gates

- **强制审查点**: 每个 Execution Wave 完成后 `ssf execution review` 录 receipt。W1 必须先 pass,W2 才能开;W2 必须先 pass,W3 才能开。
- **阻塞类别**: 任何 wave 的 receipt = `fail`、缺失、过 plan revision。
- **收口条件**: W1 / W2 / W3 三个 receipt 都是 `pass`,且总体 Verification Dimensions 全 PASS。

## Escalation Rules

- **何时回退到 `specifying`**:
  - 用户要求改 avatar 池为 sex-aware(需引入 sex 字段,改 schema)— 改 design 决策
  - 用户要求加产后冷却 / 城镇人口硬上限(超出 R-2 范围)— 改 spec
  - 用户要求加 admin 端点手动触发迁移(超出 R-4)— 改 spec
- **何时回退到 `bridging`**:
  - 设计需要换 `CommandLineRunner` 为 `ApplicationListener` 或 SQL migration script
  - 测试需要从 Mockito 切到 Testcontainers
- **何时不得继续实现**:
  - 任意 wave review 发现 Critical 缺陷(8% 概率被改、孕期被改、`birth()` 名字仍重名等)— 立即 fail 回到 `specifying`
  - DP-3 未批准就启动 W1
  - 启动被 `MigrateChildDataRunner` 阻塞(实测启动 > 30s)— 回到 `bridging` 改 `saveAll` 分批

## Rollback

- 实施阶段(未合并到 main):`git reset --hard <base-sha>`(W1 / W2 / W3 各自回退到 wave 开始前)。
- 合并到 main 后:revert 涉及 W1/W2/W3 的 3 个 commit,`docker compose build app && docker rm -f nblm-app && docker compose up -d app`。
- 数据回滚:迁移只动 `avatar` 字段(原值是 `👶`)和 `pregnantSince` / `dueDate`(清成 null);前者回退到 `👶` 一行 SQL,后者无副作用。

## Owner

蒋路(开发 + review)
