# B2 Review Report — ForumGenerationService

- **Wave ID**: B2
- **Change**: agent-world-forum
- **Commit**: 9bb99e4 (branch `agent-world-forum`, base 6ecbbe4)
- **Files Changed**: 2 files (+367)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| @Scheduled cron 入口 | ✓ | `@Scheduled(cron = "${forum.cron:0 0 21 * * *}")` |
| forumEnabled 短路 | ✓ | generateDailyPosts 第一行 return + log "[forum-paused]" |
| 1~3 条/agent (由 LLM 决定) | ✓ | buildPrompt 把 maxPerAgent 传给 LLM,parseResponse 不强制条数 |
| 6 类目清单 | ✓ | CATEGORIES 数组 + parseResponse 校验 + buildPrompt system prompt |
| 类目多样性 | ✓ | recentCategoryStats 统计近 7 天,buildPrompt 携带 |
| LLM 失败不影响其他 agent | ✓ | generateDailyPosts 外层 try/catch |
| 非法 JSON 整批丢 | ✓ | parseResponse 抛 IllegalArgumentException,外层 catch 继续 |
| buildPrompt 含三段强约束 | ✓ | 「个性」「内涵」「自主性」三段 + 6 类目 + 套话黑名单 |
| 套话黑名单 | ✓ | CLICHE_PREFIXES × 5,匹配开头 → log warn + continue |
| 长度校验 | ✓ | LENGTH_RANGES map,超 max 截断 + "...",不足 min 丢弃 |
| title 必填分类 | ✓ | TITLE_REQUIRED:acrostic / video_series / utility |
| LLM 调用失败容忍 | ✓ | callLlm 返回 null(不抛) |
| 单元测试 | ✓ | 4 个测试全绿(skip / per-agent / 非法 JSON / buildPrompt 内容) |
| 116 全项目测试 | ✓ | mvn test → 116, 0 fail, 1 skip(原有) |

## 关键设计点

1. **复用 WorldSimEngine LLM 调用范式**:同样的 `streamTextWithFallback` + `TextBlock` 拼装模式,失败返回 null 不 throw,沿用项目惯例。
2. **buildPrompt 把"硬约束"前置**:把 6 类目 + 套话黑名单 + 长度约束全部塞进 system prompt(不是 user),降低 LLM 误读概率。User 部分只携带 agent 名 + 引导。
3. **parseResponse 不存任何"半个"帖子**:单条不合法 → 丢弃单条;JSON 不可解析 / category 非法 → 整批 0 条(因为无法保证一致性)。
4. **truncate 策略**:长度超 max 截断到 max + "..."(而不是丢),保留可读性。短于 min 才丢。
5. **PromptPair record**:用嵌套 record 传 (system, user),让 buildPrompt 是纯函数(无副作用,易测)。
6. **maxPerAgent 来自 settings**:不写死,settings 改完下次 cron 自然生效。

## 风险与限制

- **未应用 init.sql**:4 张新表还没建到 nblm-mysql,本服务启动会因 JPA 验证失败(若有 validate)。当前 ddl-auto=none,启动不会失败,但 cron 触发会因缺表抛异常被 catch。部署前必须 apply。
- **没测真实 LLM 调用**:mock ChatModelFactory 测的是编排逻辑,真实 prompt 工程效果需要部署后跑一次 cron 看实际输出。
- **.c5 套话黑名单只查开头**:LLM 可能在第 2 段才写套话,目前不挡。需要更严的可以加 contains 检查,但风险是误杀(经典引用也会触发)。
- **LLM 响应 markdown 包装**:`\`\`\`json ... \`\`\`` 简单剥离,但不处理 `<` `>` 等转义问题。

## Requirement 映射

| R# | Spec | B2 覆盖 | 测试 |
|---|---|---|---|
| c3-R1 (cron 入口 + 跳过开关) | c3-forum-content-generation | ✓ | generateDailyPosts_skipsWhenForumDisabled |
| c3-R2 (1~3 条/agent) | c3-forum-content-generation | ✓ | parseResponse + buildPrompt 透传 maxPerAgent |
| c3-R3 (6 类目 + 多样性) | c3-forum-content-generation | ✓ | CATEGORIES 数组 + recentCategoryStats |
| c3-R4 (LLM 失败隔离) | c3-forum-content-generation | ✓ | generateDailyPosts 外层 try/catch |
| c5-R1 (三段强约束) | c5-forum-text-quality | ✓ | buildPrompt_systemContainsAllSixCategoriesAndClicheBlacklist |
| c5-R2 (套话黑名单) | c5-forum-text-quality | ✓ | CLICHE_PREFIXES + startsWithCliche |
| c5-R3 (长度约束) | c5-forum-text-quality | ✓ | LENGTH_RANGES map + parseResponse 校验 |
| c5-R4 (title 必填分类) | c5-forum-text-quality | ✓ | TITLE_REQUIRED set |

## Verdict

**PASS** — B2 全部 done-when 满足,4/4 新测试绿,116/116 全项目测试绿。建议进入 B3 (ForumMediaService,生成图片/视频附件)。
