# Design: 小镇天气系统 + 加长小说章节

## Context

### 当前状态
- **天气**:`WorldSimEngine.dailySettlement()` 在第 68~70 行按季节从 `SEASON_WEATHER`(`Map<String,String[]>`)随机抽一个字符串,存入 `WorldDailyReport.weather` 与(经 `AutonomousRunner`)`WorldSettings.weather`。天气**无温度、无连续性**(每日独立随机),仅作为 `writeNarrative()` 与 `AutonomousRunner.buildPrompt()` 的一句点缀,**不影响任何结算概率**。
- **章节**:`produce(e, date, "chapter", "novel", style, tok)`(第 515 行)的 system prompt 写死 `"...写「第 N 章」的章节标题与一段 80~140 字的精彩梗概。"`。`call()`(第 669 行)通过 `modelFactory.streamTextWithFallback(defaultTextModel, messages)` 调用,**不传任何 GenerateOptions**,故无 max_tokens。
- **概率站点**:创作触发 `rnd(100) >= 20 + lv*5`(271 行);婚礼 `rnd(100) >= 35`(385 行);互评 `rnd(100) >= 50`(312 行);突发事件 `rnd(100) >= 60`(455 行)。
- **实体**:`WorldDailyReport` 与 `WorldSettings` 已各有 `season`/`weather` 列,但**无温度列**。
- **前端**:`index.html` 的 `weatherEmoji()`(3692 行)按状况关键字映射 emoji;时钟区(3170 行)与日报详情(3717 行)渲染 `季节·天气`,**无温度**。
- **模型 options**:`GenerateOptions.builder().maxTokens(int)` 可用(已在 `XhsService`/`NewsService`/`WebSearchTool` 用于 `additionalBodyParams`);`ChatModelBase.stream(messages, tools, options)` 接受第三参 options。但 `ChatModelFactory.streamText`/`streamTextWithFallback` 目前**不透传 options**。

### 约束
- **DP-0**:合成单一变更;full 规模;天气=完整模型+前端;章节=800~1500 字完整正文;novel 显式 max_tokens;每个决策点确认。
- **DB**:`ddl-auto: none`,init.sql 仅在全新库生效 → 新增温度列需**手动迁移**(提供 SQL 给用户),init.sql 同步更新供未来新库。
- **免费模型**:默认 `glm-4.7-flash`,GLM Flash 系列单次输出上限约 4096 tokens,3000 max_tokens 安全。
- **向后兼容**:旧日报/旧设置无温度 → 后端读取容错、前端优雅降级。
- **不动沙盒**:`SandboxRunner` 是独立纯规则引擎,不引入天气模型。

### Stakeholders
- 小镇围观用户(看日报/时钟的沉浸感)、管理员(token/成本可见)、项目作者(维护成本)。

## Goals
1. 天气有**温度 + 逐日连续演变**,并**真实影响**创作/婚礼/突发事件。
2. 天气(状况+温度)注入日报与居民 prompt,并在前端可见。
3. novel 章节产出 800~1500 字完整正文,受显式 max_tokens 保护。
4. 全程零破坏:旧数据、其他产物、沙盒均不受负面影响。

## Decisions

### D1: 温度模型 —— 季节基准 + 单日有界漂移 + 区间钳制
- **Choice**:新增 `WeatherState`(轻量值对象:`condition` String + `temp` int)。每日温度 = `clamp(前一日温度 + 随机漂移[-5,5] + 向季节基准的回归项, 季节区间下限, 季节区间上限)`。季节基准/区间用常量表:冬 base≈0(-10~10)、春 base≈15(5~25)、夏 base≈30(22~38)、秋 base≈17(8~26)。冷启动(无历史温度)以季节 base 为初值。
- **Rationale**:漂移+回归=随机游走带均值回复,既连续又不会跑偏;钳制保证落在季节合理区间(满足 spec"温度落在季节合理区间")。纯本地计算,零 token。
- **Alternatives**:①正弦年周期精确建模——过度设计,小镇按"天"跳进,无需物理精度;②每日独立随机温度——违背"连续性"需求。

### D2: 天气状况的马尔可夫式选取
- **Choice**:保留 `SEASON_WEATHER` 季节状况集合,但选取时**受昨日状况影响**:定义"晴/秋高气爽=好、多云/阴=中、小雨/雷阵雨/寒风/花粉=差、台风/大雪=极端"四档,今日以"倾向于停留在相近档"的加权概率抽取(如昨晴→今日更可能晴/多云,昨雨→更可能阴/雨)。
- **Rationale**:低成本实现状况连续性,符合 spec"状况选取概率受昨日影响"。
- **Alternatives**:完整转移矩阵——维护成本高,收益有限。

### D3: 天气效应集中为一个 `WeatherEffect`,在结算入口计算一次并向下传递
- **Choice**:新增内部方法 `weatherEffect(WeatherState)` 返回一个轻量结构(记录:户外创作概率增量 `outdoorDelta`、室内创作概率增量 `indoorDelta`、婚礼概率增量 `weddingDelta`、是否极端 `extreme`)。在 `dailySettlement` 顶部算出后,传入 `maybeCreate(...)`、`weddings(...)`、`randomEvents(...)`。概率处用 `clampProb(base + delta, 0, 100)` 应用。
  - `maybeCreate`:novel/music 用 `indoorDelta`,image/video 用 `outdoorDelta`,叠加到 `20 + lv*5` 阈值上。
  - `weddings`:极端天气时把 `>= 35` 提高(降低成婚率)。
  - `randomEvents`:极端天气追加一条天气型突发事件(独立于原 60% 突发,或在其中择一天气分支)。
- **Rationale**:把"天气→概率"的映射收敛到一处,签名改动可控、可测;避免把温度/状况散落到各方法各自解释。
- **Alternatives**:把 `WeatherState` 直接透传到每个方法各自判断——逻辑分散、难测、易不一致。

### D4: 温度落库 —— 两张表各加 `temperature INT NULL`
- **Choice**:`WorldDailyReport` 加 `Integer temperature`;`WorldSettings` 加 `Integer temperature`。`Integer`(可空)以兼容旧行(null=未知)。`AutonomousRunner` 第 97 行同步镜像时一并写 `s.setTemperature(r.getTemperature())`。init.sql 两表加列;另出手动 `ALTER TABLE` 迁移 SQL 交用户执行。
- **Rationale**:可空整型最小侵入且天然兼容旧数据。
- **Alternatives**:把温度塞进 `statsJson`——查询/前端解析别扭,且 `WorldSettings` 无 stats 字段。

### D5: max_tokens 透传 —— 给 ChatModelFactory 增加带 options 的重载
- **Choice**:在 `ChatModelFactory` 增加 `streamText(model, messages, GenerateOptions)` 与 `streamTextWithFallback(primary, messages, GenerateOptions)` 重载(原无参重载委托到新重载传 `null`,保持既有调用零改动)。`WorldSimEngine.call()` 增加一个可选 `GenerateOptions` 参数(或新增 `callWithOptions`),novel 分支传 `GenerateOptions.builder().maxTokens(3000).build()`,其余调用传 null。
- **Rationale**:复用现有降级链与重试逻辑;options 仅 novel 用,不污染其他调用(满足"非 novel 不受影响")。
- **Alternatives**:novel 绕过 factory 直接 `forModel().stream(...)`——会丢失降级/重试,退化可靠性。

### D6: 前端温度展示 + 优雅降级
- **Choice**:时钟区(3170 行)与日报详情(3717 行)在有温度时追加 `${temp}℃`;`worldSettings.temperature`/`r.temperature` 为 null/undefined 时不渲染温度片段。emoji 逻辑复用 `weatherEmoji()` 不变。
- **Rationale**:满足 spec"缺温度只显状况、不出现 undefined"。
- **Alternatives**:温度缺失时显示"—"——无必要的视觉噪声。

## Risks And Trade-Offs
- **Token/成本上升**:novel 从 ~140 字→~1500 字,单次输出 token ×5~10。缓解:仅 novel 加长;max_tokens=3000 封顶;免费模型成本为 0(仅在切付费模型时才有费用,届时管理员可见)。
- **概率叠加导致创作骤降**:极端天气把户外创作压太低可能让小镇"太安静"。缓解:delta 取小幅有界值(如 ±10~15),并保证 `clampProb` 不越界;室内创作反向补偿。
- **迁移遗漏**:用户忘记跑 ALTER → 启动即报列不存在。缓解:温度列 `NULL` 且实体读取容错;在交付说明中显著标注迁移 SQL(与既有 `skills_json` 迁移一致的已知约束)。
- **马尔可夫状况权重主观**:好/中/差/极端档的转移权重是经验值。缓解:集中为常量,便于日后微调;不影响正确性。
- **max_tokens 与降级链**:降级到其他免费模型时 options 需同样透传。缓解:重载在 `streamTextWithFallback` 层统一带上 options,对链上每个候选生效。
