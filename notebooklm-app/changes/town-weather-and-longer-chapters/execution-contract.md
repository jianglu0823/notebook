# Execution Contract: 小镇天气系统 + 加长小说章节

> 本契约是规划(proposal/specs/design/tasks)到实现的唯一交接。实现须严格遵守此处的批次、约束与测试义务。任何越界需回退规划。

## Intent Lock

把智能体小镇的天气从"每日随机抽词"升级为**带温度、逐日连续演变、并真实影响居民创作/婚礼/突发事件**的天气模型并在前端可视化;同时把居民 `novel` 章节从"80~140 字梗概"改为**800~1500 字完整正文**,并为 novel 调用显式设置 `max_tokens≈3000`。

**Scale**: full。**Change dir**: `changes/town-weather-and-longer-chapters`。

## Scope Fence(越过即回退规划)

**In**:天气模型(状况+温度+逐日演变)、天气对结算概率的有界影响、天气注入日报叙事与居民 prompt、两表加 `temperature` 列 + 迁移、novel 章节加长 + max_tokens、前端时钟区/日报详情温度展示。

**Out(禁止触碰)**:
- 天气对经济/工资的直接影响(不改 `payWages`/工资逻辑)。
- 天气预报/未来多日预测 UI。
- 天气地理分区(全镇统一天气)。
- 歌词/画作/短片的长度调整(仅 novel 加长)。
- `SandboxRunner` 沙盒内的天气(独立纯规则引擎,不引入天气模型)。

## Approved Requirements → Test Obligations 映射

### town-weather

| # | Requirement (SHALL/MUST) | 落点批次 | 验证义务 |
|---|--------------------------|----------|----------|
| W1 | 连续演变天气模型:含状况+温度,基于昨日+季节+漂移 | B3.1/B3.2 | 心算/日志:连续两日温差≤±5,状况随昨日档位倾向 |
| W2 | 温度落季节区间(冬-10~10/春5~25/夏22~38/秋8~26) | B3.1/B3.2 | `nextWeather` clamp 到季节 min/max;日志观测多日不越界 |
| W3 | 冷启动(无历史温度)以季节基准初值,不抛异常 | B3.2/B3.4 | prev==null 分支;旧库首跑无异常 |
| W4 | 天气影响结算,概率有界 [0,100] | B3.3/B3.5/B3.6 | `clampProb` 保证 0~100;极端→户外降/室内升/婚礼降 |
| W5 | 极端天气触发天气型突发 news | B3.7 | 极端日 news 出现 type=weather 条目 |
| W6 | 好天气不抑制创作与社交 | B3.3/B3.5 | GOOD 档 outdoorDelta≥0、indoorDelta≥0 |
| W7 | 天气+温度注入日报叙事 prompt | B3.8 | writeNarrative user prompt 含温度片段 |
| W8 | 天气+温度注入居民 prompt | B4.2 | buildPrompt 含温度片段 |
| W9 | 温度持久化到 report + settings | B1.1/B1.2/B3.4/B4.1 | 结算写 report.temperature;runner 镜像 settings.temperature |
| W10 | 旧数据缺温度容错读取,前端优雅降级 | B1(Integer可空)/B5 | null 不报错;前端不出 undefined/null |
| W11 | 前端时钟区展示状况+温度 | B5.1 | 有温度显示 ℃;缺失只显状况 |
| W12 | 前端日报详情展示状况+温度 | B5.2 | 同上 |

### resident-novel-length

| # | Requirement (SHALL/MUST) | 落点批次 | 验证义务 |
|---|--------------------------|----------|----------|
| N1 | novel 产 800~1500 字完整正文,结构不变(kind=chapter,title/content/quality) | B3.9 | prompt 要求完整正文;产物字段不变 |
| N2 | 仅 novel 加长,music/image/video 不变 | B3.9 | 仅 novel 分支改 prompt |
| N3 | novel 调用带显式 max_tokens≈3000 | B2/B3.9 | novel 传 `GenerateOptions.maxTokens(3000)` |
| N4 | 非 novel 调用不受 max_tokens 影响 | B2/B3.9 | 其余 call 传 null;options=null 行为不变 |
| N5 | 正文不被截断(max_tokens 高于字数 token 估算) | B3.9 | 3000 > ~1500 字 token 估算,安全 |

**覆盖核查**:12 条 town-weather + 5 条 resident-novel-length 需求全部映射到批次且各有验证义务,无遗漏、无未映射。

## Architecture / Interface / Dependency Constraints(来自 design D1-D6)

- **D1**:温度 = `clamp(prev + drift[-5,5] + 向季节基准回归, min, max)`;`WeatherState{condition,temp}`。纯本地零 token。
- **D2**:状况马尔可夫式四档(好/中/差/极端)加权抽取。
- **D3**:天气效应收敛为单个 `weatherEffect(WeatherState)→WeatherEffect{outdoorDelta,indoorDelta,weddingDelta,extreme}`,结算入口算一次向下传;概率处统一 `clampProb`。**禁止**把 WeatherState 散落到各方法各自解释。
- **D4**:两表 `Integer temperature`(可空);init.sql 同步 + 手动 ALTER 交用户;`ddl-auto: none` → 现库须手动迁移。
- **D5**:`ChatModelFactory` 加带 `GenerateOptions` 重载,原无参重载委托传 null(既有调用零改动);options 须对降级链每个候选生效。**禁止** novel 绕过 factory 直调(会丢降级/重试)。
- **D6**:前端 temperature 为 null/undefined 时不渲染温度片段,复用 `weatherEmoji()`。

## Execution Batches(依赖顺序)

- **B1 实体+迁移**(无依赖):1.1 WorldSettings、1.2 WorldDailyReport、1.3 init.sql、1.4 migration.sql。**完成定义**:`mvn -q -o compile` 通过 + migration.sql 就绪。
- **B2 ChatModelFactory 透传**(无依赖,可与 B1 并行):2.1 streamText 重载、2.2 streamTextWithFallback 重载。**完成定义**:编译通过 + 既有调用零改动。
- **B3 WorldSimEngine**(依赖 B1、B2):3.1 类型+常量、3.2 nextWeather、3.3 weatherEffect、3.4 dailySettlement 接入、3.5 maybeCreate、3.6 weddings、3.7 randomEvents、3.8 writeNarrative、3.9 novel 加长+max_tokens。**完成定义**:编译通过 + W1-W7/N1-N5 落点就位。
- **B4 AutonomousRunner**(依赖 B1、B3):4.1 镜像 temperature、4.2 buildPrompt 注入。**完成定义**:编译通过 + W8/W9(settings 侧)就位。
- **B5 前端**(依赖 B1):5.1 时钟区、5.2 日报详情/列表。**完成定义**:浏览器验证有温度显示 ℃、缺失只显状况无 undefined(W10-W12)。

## Review Gates

- 每批实现后由 code-reviewer 出 wave 评审 receipt(spec 合规 + 代码质量),`pass` 方可进入依赖批次或收尾。
- B3 是核心批,评审重点:概率 clamp 边界、天气效应集中、novel 仅改自身分支不影响其他产物。

## Test Evidence(交付验证)

1. 编译:`mvn -q -o compile`(在 notebooklm-app/ 下)全绿。
2. 迁移:用户在 docker exec MySQL 内执行 `migration.sql`(两条 ALTER;DB 写入需用户授权,用 `!` 前缀)。
3. 重建:用户确认后重建 app 容器。
4. 运行验证:开启自主行动跑数日,观察日报温度逐日连续、极端天气触发 weather 型 news、novel 章节达 800~1500 字、前端时钟/日报显示温度、旧日报无温度不报错不出 undefined。

## Escalation / Rewind Rules

- 若实现中发现某需求无法在既定批次落地或与 design 决策冲突 → 停止,回退 spec-writer/design。
- 若 scope fence 被迫扩大(如需改工资逻辑或沙盒)→ 停止,回 proposal 重新界定。
- **未映射需求**:无(覆盖核查全绿)。
- DB 迁移/容器重建为用户手动步骤,实现批次不含这两步的自动执行。

## Approval Gate (DP-3)

实现前须经用户显式批准本契约。**无 DP-3 记录不得开始任何实现编辑。**
