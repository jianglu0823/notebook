# Tasks: 小镇天气系统 + 加长小说章节

> 依赖顺序:Batch 1(实体+迁移)→ Batch 2(ChatModelFactory options 透传)→ Batch 3(WorldSimEngine 天气模型+效应+章节加长)→ Batch 4(AutonomousRunner 镜像+prompt)→ Batch 5(前端可视化)。
> 每步 2~5 分钟、原子、无占位符。所有 Java 路径相对 `notebooklm-app/`,SQL 相对仓库根。

## File Structure

- **Modify** `src/main/java/io/llmnote/world/WorldSettings.java` — 加 `Integer temperature`(可空)列。
- **Modify** `src/main/java/io/llmnote/world/WorldDailyReport.java` — 加 `Integer temperature`(可空)列。
- **Modify** `src/main/java/io/llmnote/llm/ChatModelFactory.java` — 加带 `GenerateOptions` 的 `streamText`/`streamTextWithFallback` 重载,原重载委托传 null。
- **Modify** `src/main/java/io/llmnote/world/WorldSimEngine.java` — 新增 `WeatherState`/`WeatherEffect` 内部类型、`nextWeather()`、`weatherEffect()`;改造 `dailySettlement`/`maybeCreate`/`weddings`/`randomEvents`/`writeNarrative`/`call`;novel prompt 加长 + max_tokens。
- **Modify** `src/main/java/io/llmnote/world/AutonomousRunner.java` — 镜像 temperature;buildPrompt 注入温度。
- **Modify** `src/main/resources/static/index.html` — 时钟区(3170)与日报详情(3687/3717)展示温度,缺失优雅降级。
- **Modify** `../infra/mysql/init.sql` — 两表加 `temperature INT NULL` 列(供未来新库)。
- **Create** `.claude/docs/specs/town-weather-and-longer-chapters/migration.sql` — 交用户手动执行的 `ALTER TABLE` 迁移。

## Interfaces

- **Produces (Batch 1)**:`WorldSettings.temperature: Integer`、`WorldDailyReport.temperature: Integer`(getter/setter 由 Lombok `@Data` 生成)。
- **Produces (Batch 2)**:`ChatModelFactory.streamText(ChatModelBase, List<Msg>, GenerateOptions): List<ChatResponse>`、`ChatModelFactory.streamTextWithFallback(String, List<Msg>, GenerateOptions): List<ChatResponse>`。
- **Produces (Batch 3)**:`WorldSimEngine.WeatherState`(record: `String condition`, `int temp`)、`WorldSimEngine.WeatherEffect`(record: `int outdoorDelta`, `int indoorDelta`, `int weddingDelta`, `boolean extreme`)、`nextWeather(String season, WeatherState prev): WeatherState`、`weatherEffect(WeatherState): WeatherEffect`、`call(String, String, long[], GenerateOptions): String`。
- **Consumes**:Batch 3 消费 Batch 1 的 setter 与 Batch 2 的 options 重载;Batch 4 消费 Batch 1 的 `getTemperature/setTemperature`;Batch 5 消费序列化后的 `temperature` 字段(实体经现有 REST 直接 JSON 序列化,无需改 controller)。

---

## Batch 1 — 实体温度字段 + 迁移 SQL

**Depends on: (none)**

### Task 1.1 — WorldSettings 加 temperature 列
- **File**: `src/main/java/io/llmnote/world/WorldSettings.java`
- **Phase 1 (Red)**:确认当前无 temperature 字段(已读:仅 season/weather)。
- **Phase 2 (Green)**:在 `weather` 字段(第 45 行)之后新增:
  ```java
  /** 当前温度(℃,可空=未知,兼容旧行)。 */
  @Column(name = "temperature")
  private Integer temperature;
  ```
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:字段可空,旧行读为 null。
- **Interfaces**: Produces `WorldSettings.temperature`。

### Task 1.2 — WorldDailyReport 加 temperature 列
- **File**: `src/main/java/io/llmnote/world/WorldDailyReport.java`
- **Phase 1 (Red)**:确认无 temperature(已读:仅 season/weather)。
- **Phase 2 (Green)**:在 `weather` 字段(第 30 行)之后新增:
  ```java
  /** 当日温度(℃,可空=未知,兼容旧日报)。 */
  @Column(name = "temperature")
  private Integer temperature;
  ```
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:可空整型,兼容旧数据。
- **Interfaces**: Produces `WorldDailyReport.temperature`。

### Task 1.3 — init.sql 两表加列(未来新库)
- **File**: `../infra/mysql/init.sql`
- **Phase 1 (Red)**:确认现表无 temperature(已读第 313、343 行)。
- **Phase 2 (Green)**:
  - `agent_world_settings`(第 314 行 `weather` 之后)加:`temperature INT NULL COMMENT '当前温度(℃,可空)',`
  - `world_daily_report`(第 344 行 `weather` 之后)加:`temperature INT NULL COMMENT '当日温度(℃,可空)',`
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:目视列语法正确(逗号、位置)。
- **Phase 5 (Commit-ready)**:仅全新库生效,不影响现库。

### Task 1.4 — 手动迁移 SQL 交付文件
- **File**: `changes/town-weather-and-longer-chapters/migration.sql`(Create)
- **Phase 1 (Red)**:现库无 temperature 列,启动读取 null 容错但需列存在方能写入。
- **Phase 2 (Green)**:写入两条 ALTER:
  ```sql
  ALTER TABLE agent_world_settings ADD COLUMN temperature INT NULL COMMENT '当前温度(℃,可空)' AFTER weather;
  ALTER TABLE world_daily_report   ADD COLUMN temperature INT NULL COMMENT '当日温度(℃,可空)' AFTER weather;
  ```
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:语法自检;交付说明提示用户用 `!` 前缀在 docker exec 内执行(DB 写入需用户授权)。
- **Phase 5 (Commit-ready)**:交付文档标注迁移必跑,否则结算写 temperature 报列不存在。

---

## Batch 2 — ChatModelFactory options 透传

**Depends on: (none,可与 Batch 1 并行)**

### Task 2.1 — streamText 加 options 重载
- **File**: `src/main/java/io/llmnote/llm/ChatModelFactory.java`
- **Phase 1 (Red)**:现 `streamText(model, messages)`(第 107 行)第三参写死 `null`,无法传 max_tokens。
- **Phase 2 (Green)**:
  - 顶部 import `io.agentscope.core.model.GenerateOptions;`。
  - 新增重载 `public List<ChatResponse> streamText(ChatModelBase model, List<Msg> messages, GenerateOptions options)`:与原方法体一致,仅把 `model.stream(messages, List.of(), null)` 改为 `model.stream(messages, List.of(), options)`。
  - 原 `streamText(model, messages)` 改为委托:`return streamText(model, messages, null);`。
- **Phase 3 (Refactor)**:重试/退避逻辑只保留在带 options 的重载里,原方法仅委托。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过;既有调用方零改动。
- **Phase 5 (Commit-ready)**:options=null 时行为与变更前完全一致。
- **Interfaces**: Produces `streamText(...,GenerateOptions)`。

### Task 2.2 — streamTextWithFallback 加 options 重载
- **File**: `src/main/java/io/llmnote/llm/ChatModelFactory.java`
- **Phase 1 (Red)**:现 `streamTextWithFallback(primary, messages)`(第 130 行)不透传 options,降级链每个候选都拿不到 max_tokens。
- **Phase 2 (Green)**:
  - 新增重载 `public List<ChatResponse> streamTextWithFallback(String primaryModel, List<Msg> messages, GenerateOptions options)`:复制原方法体,把降级循环内 `streamText(forModel(m), messages)` 改为 `streamText(forModel(m), messages, options)`。
  - 原 `streamTextWithFallback(primaryModel, messages)` 改为委托:`return streamTextWithFallback(primaryModel, messages, null);`。
- **Phase 3 (Refactor)**:确保 options 对链上每个候选生效(D5 风险缓解)。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:options=null 保持既有降级行为。
- **Interfaces**: Produces `streamTextWithFallback(...,GenerateOptions)`;Consumes Task 2.1。

---

## Batch 3 — WorldSimEngine 天气模型 + 效应 + 章节加长

**Depends on: Batch 1, Batch 2**

### Task 3.1 — 新增 WeatherState/WeatherEffect + 季节常量
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`
- **Phase 1 (Red)**:现仅 `SEASON_WEATHER`(第 37 行),无温度模型、无档位分类。
- **Phase 2 (Green)**:在类内(`SEASON_WEATHER` 之后)新增:
  - `record WeatherState(String condition, int temp) {}`
  - `record WeatherEffect(int outdoorDelta, int indoorDelta, int weddingDelta, boolean extreme) {}`
  - 季节温度基准/区间常量:`SEASON_TEMP_BASE`(冬 0/春 15/夏 30/秋 17)、`SEASON_TEMP_MIN`、`SEASON_TEMP_MAX`(冬 -10~10/春 5~25/夏 22~38/秋 8~26),用 `Map<String,Integer>`。
  - 档位集合常量:`GOOD`(晴/秋高气爽)、`BAD`(小雨/雷阵雨/寒风/花粉飞舞)、`EXTREME`(台风/大雪)——用 `Set<String>`,其余归"中"。
- **Phase 3 (Refactor)**:常量集中在字段区,便于日后调权。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:纯常量,无行为改动。
- **Interfaces**: Produces `WeatherState`、`WeatherEffect`、季节温度常量。

### Task 3.2 — nextWeather() 连续演变
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`
- **Phase 1 (Red)**:现天气 = 每日独立随机抽词(第 70 行),无连续性、无温度。
- **Phase 2 (Green)**:新增 `WeatherState nextWeather(String season, WeatherState prev)`:
  - 温度:`base = SEASON_TEMP_BASE.get(season)`;冷启动(prev==null)`temp = base`;否则 `temp = clamp(prev.temp() + rnd漂移[-5,5] + 向 base 回归项((base - prev.temp())/4), min, max)`。
  - 状况:从 `SEASON_WEATHER[season]` 选取,按 prev 档位加权(prev 好→更可能好/中;prev 差→更可能中/差;prev 极端→更可能差/中;冷启动均匀随机)。用简单 tier→候选加权表实现。
- **Phase 3 (Refactor)**:漂移/回归/钳制封装为局部计算,状况加权用小 helper。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过;心算温度不越区间。
- **Phase 5 (Commit-ready)**:满足 spec 连续演变 + 季节区间 + 冷启动不抛异常。
- **Interfaces**: Produces `nextWeather()`;Consumes Task 3.1。

### Task 3.3 — weatherEffect() 天气效应
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`
- **Phase 1 (Red)**:现天气不影响任何概率。
- **Phase 2 (Green)**:新增 `WeatherEffect weatherEffect(WeatherState w)`:
  - 极端(condition ∈ EXTREME):`outdoorDelta = -15`(压低户外),`indoorDelta = +8`(略升室内),`weddingDelta = +20`(阈值升→成婚率降),`extreme = true`。
  - 好(∈ GOOD):`outdoorDelta = +8`,`indoorDelta = 0`,`weddingDelta = 0`,`extreme = false`。
  - 差(∈ BAD):`outdoorDelta = -8`,`indoorDelta = +4`,`weddingDelta = 0`,`extreme = false`。
  - 中(其余):全 0。
- **Phase 3 (Refactor)**:delta 取小幅有界值(±8~20),避免创作骤降(D3/风险缓解)。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:映射收敛于一处。
- **Interfaces**: Produces `weatherEffect()`;Consumes Task 3.1。

### Task 3.4 — dailySettlement 接入天气模型
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`(第 64~137 行)
- **Phase 1 (Red)**:第 68~70 行随机抽词;`maybeCreate`/`weddings`/`randomEvents` 未收天气;报告未写 temperature。
- **Phase 2 (Green)**:
  - 第 68~70 行替换:读上一日报(`reportRepo` 取 date-1 或最近一条)构造 `prev WeatherState`(prev 的 condition=旧 weather,temp=旧 temperature,可空则 null),`WeatherState ws = nextWeather(season, prev)`;`String weather = ws.condition()`;`Integer temperature = ws.temp()`。
  - 顶部算 `WeatherEffect eff = weatherEffect(ws);`。
  - `maybeCreate(e, date, skills, tok)` 调用改为 `maybeCreate(e, date, skills, tok, eff)`。
  - `weddings(active, date, news, highlights)` → 追加 `eff` 参数。
  - `randomEvents(active, date, news)` → 追加 `ws, eff` 参数。
  - `writeNarrative(date, season, weather, ...)` → 追加 `temperature` 参数。
  - `r.setTemperature(temperature)`;`log.info` 追加 `temp={}`。
- **Phase 3 (Refactor)**:prev 读取封装为 helper `loadPrevWeather()`,容错旧数据 null。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:结算写入 temperature;旧数据无温度时冷启动。
- **Interfaces**: Consumes Task 1.2、3.2、3.3。

### Task 3.5 — maybeCreate 应用天气效应
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`(第 259~280 行)
- **Phase 1 (Red)**:第 271 行阈值 `rnd(100) >= 20 + lv*5`,与天气无关。
- **Phase 2 (Green)**:
  - 方法签名加 `WeatherEffect eff`。
  - 抽出技能后,按技能类型取 delta:novel/music 用 `eff.indoorDelta()`,image/video 用 `eff.outdoorDelta()`。
  - 触发判定改为:`int thr = clampProb(20 + lv*5 - delta, 0, 100); if (rnd(100) >= thr) return null;`(delta 为正=更易触发,故减)。
- **Phase 3 (Refactor)**:新增 `static int clampProb(int v, int lo, int hi)`(或复用现有 `clamp`)。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过;心算阈值落 [0,100]。
- **Phase 5 (Commit-ready)**:满足 spec 极端抑制户外/升室内、概率有界。
- **Interfaces**: Consumes Task 3.3。

### Task 3.6 — weddings 应用天气效应
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`(第 ~373~398 行)
- **Phase 1 (Red)**:第 385 行 `rnd(100) >= 35`,与天气无关。
- **Phase 2 (Green)**:签名加 `WeatherEffect eff`;判定改为 `int thr = clampProb(35 + eff.weddingDelta(), 0, 100); if (rnd(100) >= thr) continue;`(极端 +20 → 阈值升 → 成婚率降)。
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:满足 spec 极端降低婚礼概率。
- **Interfaces**: Consumes Task 3.3。

### Task 3.7 — randomEvents 追加天气型突发事件
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`(第 453~487 行)
- **Phase 1 (Red)**:第 455 行 60% 普通突发,无天气分支。
- **Phase 2 (Green)**:签名加 `WeatherState ws, WeatherEffect eff`;方法开头:若 `eff.extreme()` 则以一定概率(如 50%)生成一条天气型 news(台风→"台风过境,集市摊棚受损";大雪→"大雪封路,居民闭门取暖";雷阵雨→按 EXTREME 归类可复用),`addNews(news,"weather",desc)` 后 `return`(择一分支)或继续原逻辑。保留原 60% 普通突发。
- **Phase 3 (Refactor)**:天气事件文案用 `pick(...)` 按 condition 分支。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:满足 spec 极端触发天气型突发新闻。
- **Interfaces**: Consumes Task 3.1、3.3。

### Task 3.8 — writeNarrative 注入温度
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`(第 624~646 行)
- **Phase 1 (Red)**:第 639 行 prompt 含 `天气:` 无温度。
- **Phase 2 (Green)**:签名加 `Integer temperature`;user prompt 的天气片段改为 `天气:" + weather + (temperature != null ? "," + temperature + "℃" : "")`;兜底文案(第 645 行)同样带温度(有则拼)。
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:满足 spec 日报叙事含天气与温度。
- **Interfaces**: Consumes Task 3.4。

### Task 3.9 — novel 章节加长 + max_tokens
- **File**: `src/main/java/io/llmnote/world/WorldSimEngine.java`(第 519 行 + 515/525/669 行)
- **Phase 1 (Red)**:第 519 行写死"80~140 字精彩梗概";`call`(第 669 行)不传 options。
- **Phase 2 (Green)**:
  - 第 519 行 novel 分支改为:`"你是小说家的创作助手。请为一部连载小说写「第 " + seq + " 章」:先给一个章节标题,再写一段 800~1500 字的完整章节正文(含情节推进、场景描写、人物对白与心理),而非梗概。"`。
  - `call` 加重载 `call(String system, String user, long[] tok, GenerateOptions options)`:方法体同原,`streamTextWithFallback(..., messages)` → `streamTextWithFallback(..., messages, options)`;原 `call(system,user,tok)` 委托传 null。
  - `produce()` 中(第 525 行)novel(theme=="novel")传 `GenerateOptions.builder().maxTokens(3000).build()`,其余传 null:即 `String raw = call(system, user, tok, "novel".equals(theme) ? GenerateOptions.builder().maxTokens(3000).build() : null);`。
  - 顶部 import `io.agentscope.core.model.GenerateOptions;`。
- **Phase 3 (Refactor)**:song/artwork 分支不变,只 novel 加长 + max_tokens。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:满足 resident-novel-length 两个 Requirement:仅 novel 加长、仅 novel 带 max_tokens。
- **Interfaces**: Consumes Task 2.2。

---

## Batch 4 — AutonomousRunner 镜像 + prompt 注入

**Depends on: Batch 1, Batch 3**

### Task 4.1 — 镜像 temperature 到 settings
- **File**: `src/main/java/io/llmnote/world/AutonomousRunner.java`(第 97 行)
- **Phase 1 (Red)**:第 97 行仅镜像 season/weather。
- **Phase 2 (Green)**:改为 `if (r != null) { s.setSeason(r.getSeason()); s.setWeather(r.getWeather()); s.setTemperature(r.getTemperature()); }`。
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:满足 spec settings 温度镜像。
- **Interfaces**: Consumes Task 1.1、1.2。

### Task 4.2 — buildPrompt 注入温度
- **File**: `src/main/java/io/llmnote/world/AutonomousRunner.java`(第 204~210 行)
- **Phase 1 (Red)**:第 205 行 weather 无温度。
- **Phase 2 (Green)**:第 205 行后追加温度片段,如 `String temp = s.getTemperature() == null ? "" : s.getTemperature() + "℃";`,并在第 210 行拼接串里把 `weather` 处改为 `weather + (temp.isEmpty()?"":" "+temp)`。
- **Phase 3 (Refactor)**:温度缺失时不出现空串噪声。
- **Phase 4 (Verify)**:`mvn -q -o compile` 通过。
- **Phase 5 (Commit-ready)**:满足 spec 居民 prompt 含天气与温度。
- **Interfaces**: Consumes Task 1.1。

---

## Batch 5 — 前端天气可视化(含温度 + 降级)

**Depends on: Batch 1(字段序列化即可,无需 controller 改动)**

### Task 5.1 — 时钟区展示温度
- **File**: `src/main/resources/static/index.html`(第 3170~3173 行)
- **Phase 1 (Red)**:第 3171 行天气无温度。
- **Phase 2 (Green)**:第 3171 行改为在有 `worldSettings.temperature`(非 null/undefined)时追加 `${worldSettings.temperature}℃`,如:`const weather = worldSettings.weather ? \`${weatherEmoji(worldSettings.weather)}${worldSettings.weather}${worldSettings.temperature != null ? ' ' + worldSettings.temperature + '℃' : ''}\` : '';`。
- **Phase 3 (Refactor)**:无。
- **Phase 4 (Verify)**:浏览器打开小镇,时钟区显示"季节 · emoji+状况+温度";无温度时只显状况、无 undefined。
- **Phase 5 (Commit-ready)**:满足 spec 时钟区展示 + 缺失降级。
- **Interfaces**: Consumes Task 1.1(序列化 temperature)。

### Task 5.2 — 日报详情/列表展示温度
- **File**: `src/main/resources/static/index.html`(第 3687、3717 行)
- **Phase 1 (Red)**:第 3687(列表卡)、3717(详情头)天气无温度。
- **Phase 2 (Green)**:两处 `r.weather` 后在 `r.temperature != null` 时追加 ` ${r.temperature}℃`(用 `esc`/模板保持一致风格),缺失不渲染温度片段。
- **Phase 3 (Refactor)**:复用同一判空写法,避免 undefined/null 文本。
- **Phase 4 (Verify)**:打开某日日报详情:有温度显示 emoji+状况+温度;旧日报无温度只显状况。
- **Phase 5 (Commit-ready)**:满足 spec 日报详情展示 + 缺失降级。
- **Interfaces**: Consumes Task 1.2(序列化 temperature)。

---

## 交付后置(非任务,交付说明中标注)

1. **迁移**:用户须在 docker exec MySQL 内执行 `.claude/docs/specs/town-weather-and-longer-chapters/migration.sql`(两条 ALTER),否则结算写 temperature 报列不存在(DB 写入需用户授权,用 `!` 前缀)。
2. **重建**:代码改动需重建 app 容器方生效(需用户确认后执行)。
3. **验证**:开启自主行动跑数日,观察日报温度逐日连续、极端天气突发 news、novel 章节达 800~1500 字。

---

## 完成清单(所有任务已实现并通过 wave 评审 pass)

- [x] Batch 1 — 实体温度字段 + 迁移 SQL(Task 1.1~1.4)
- [x] Batch 2 — ChatModelFactory options 透传(Task 2.1~2.2)
- [x] Batch 3 — WorldSimEngine 天气模型 + 效应 + 章节加长(Task 3.1~3.9)
- [x] Batch 4 — AutonomousRunner 镜像 + prompt 注入(Task 4.1~4.2)
- [x] Batch 5 — 前端天气可视化(Task 5.1~5.2)
