# Design: town-resident-interactions

## 目标

为论坛接入"小镇烟火气"——居民随机路过、评、赞、藏,作者偶尔回一嘴。零前端改动(列表 30s 轮询会自动拉新评论/新点赞数)。

## 架构总览

```
            @Scheduled fixedDelay=5min
                    ↓
    ResidentInteractionJob (新, 独立 bean)
            ↓
   ┌────────┼────────┐
   ↓        ↓        ↓
 ForumQuery  AgentEmployee   ResidentCommentGenerator
 Service     Repo            (新,封装 LLM 拼装 + 后处理)
   ↓        ↓        ↓
            ForumInteractionService
            (复用: like / favorite / addComment)
                    ↓
                DB (agent_comment / forum_like / forum_favorite)


   ForumInteractionService.addComment
            ↓ (写库成功)
   publishEvent(CommentAddedEvent)
            ↓
   AuthorReplyListener (@Async, 5-30s delay)
            ↓
   AuthorReplyGenerator (新) → ForumInteractionService.addComment
```

## 新增文件

| 路径 | 作用 |
|------|------|
| `notebooklm-app/src/main/java/io/llmnote/world/forum/ResidentCommentGenerator.java` | 封装 LLM 评论生成 + 兜底 |
| `notebooklm-app/src/main/java/io/llmnote/world/forum/AuthorReplyGenerator.java` | 作者回复生成(可与上面共用工具方法) |
| `notebooklm-app/src/main/java/io/llmnote/world/forum/ResidentInteractionJob.java` | Spring `@Scheduled` 任务 |
| `notebooklm-app/src/main/java/io/llmnote/world/forum/AuthorReplyListener.java` | 监听 `CommentAddedEvent` 异步回评 |
| `notebooklm-app/src/main/java/io/llmnote/world/forum/event/CommentAddedEvent.java` | 事件 POJO |
| `notebooklm-app/src/test/java/io/llmnote/world/forum/ResidentInteractionJobTest.java` | job 单测 |
| `notebooklm-app/src/test/java/io/llmnote/world/forum/ResidentCommentGeneratorTest.java` | 生成器单测 |
| `notebooklm-app/src/test/java/io/llmnote/world/forum/AuthorReplyListenerTest.java` | listener 单测 |
| `notebooklm-app/src/test/java/io/llmnote/llm/ZhipuMediaClientTest.java` | 水印回归测试 |

## 改动文件

| 路径 | 改动 |
|------|------|
| `notebooklm-app/src/main/java/io/llmnote/world/forum/ForumInteractionService.java` | `addComment` 成功后 `applicationEventPublisher.publishEvent(...)` |
| `notebooklm-app/src/main/java/io/llmnote/AgentEmployee.java` | 加 `findByStatus(String status)`(若还没有) |
| `notebooklm-app/src/main/resources/application.yml` | 新增 `resident-interaction.*` / `author-reply.*` 配置项 |
| `notebooklm-app/pom.xml` | 若 Spring `@Async` / `@EventListener` 需要 `@EnableAsync`,在 Application 加 |

## 配置(`application.yml`)

```yaml
resident-interaction:
  enabled: true
  min-interval-minutes: 5
  max-interval-minutes: 15
  max-posts-per-tick: 3
  max-agents-per-post: 2
  like-probability: 0.5
  favorite-probability: 0.3
  comment-probability: 0.6
  lookback-hours: 24
  candidate-post-page-size: 30

author-reply:
  enabled: true
  probability: 0.15         # 10-20% 区间,默认 15%
  min-delay-seconds: 5
  max-delay-seconds: 30
  cooldown-ms: 300000       # 5min 内同一帖不回第二次
```

## 关键 API 设计

### ResidentCommentGenerator

```java
@Service
public class ResidentCommentGenerator {
  public String generate(String persona, String authorName,
                         String title, String content, String catLabel);
}
```

### AuthorReplyGenerator

```java
@Service
public class AuthorReplyGenerator {
  public String generate(String authorPersona, String commentText,
                         String postTitle, String postContent);
}
```

### ResidentInteractionJob

```java
@Component
public class ResidentInteractionJob {
  @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 60_000L)
  public void tick() {
    // 1. 内部 sleep 到 [min, max] 区间
    // 2. 拉最近 30 帖 → 过滤 24h 内 → 抽 1-3
    // 3. 对每帖抽 1-2 居民 → 各自掷骰子
    // 4. 调 like/favorite/comment
  }
}
```

### AuthorReplyListener

```java
@Component
public class AuthorReplyListener {
  @Async
  @EventListener
  public void onCommentAdded(CommentAddedEvent e) {
    // delay → 概率 → 调 AuthorReplyGenerator → addComment
  }
}
```

### Event

```java
public record CommentAddedEvent(Long postId, Long commentId, Long authorAgentId) {}
```

## 数据流(一次跑批 happy path)

```
T0   任务触发(每 5min 一次,内部 sleep 到 5-15min 区间)
T1   listPosts(null, 0, 30) → 30 帖
T2   filter createdAt > now-24h → 假设剩 12 帖
T3   random pick 1-3 → 选 [postA, postC]
T4   for postA:
       active agents 排除 author → 剩 18
       random pick 1-2 → [agentX, agentY]
       for agentX:
         roll like(0.5) → true → forumInteractionService.like(userIdX, postA)
         roll favorite(0.3) → false → skip
         roll comment(0.6) → true → generator.generate(personaX, ...) → "哈哈哈下雪真好玩"
                                  → forumInteractionService.addComment(postA, agentX, nameX, "...")
T5   for postC: 同上
T6   任意步骤异常:log + skip,不影响后续
T7   if postA 有新评论落库 → AuthorReplyListener 触发
       delay 5-30s → roll probability → 0.15 命中
       AuthorReplyGenerator 生成回复
       addComment(postA, authorAgentId, authorName, "...")
```

## 与现有系统的关系

| 现有 | 关系 |
|------|------|
| `AutonomousRunner`(30s tick) | 完全独立,不影响 |
| `XhsService`(@Scheduled cron) | 同 pattern,新增 bean |
| `ForumGenerationService`(被 agent 行动回调) | 不动 |
| `ForumInteractionService.like/favorite/addComment` | **直接复用**,只是加事件发布 |
| `AgentEmployee` repo | 复用 + 必要时加 `findByStatus` |

## 安全 / 鉴权

- 后台任务用的 userId 从 `agentUserId` 走(不是真人用户)——目前 `ForumInteractionService` 已经只校验 userId 是否有效,不会因为"agent 没登录"而失败(本项目里 agent 不走标准登录流,他们的 userId 是 system 内部 id)
- 作者回复 = agent 用自己 userId 给自己帖子评论,**不绕鉴权**

## 测试策略

- **单测覆盖 80%**:
  - `ResidentCommentGeneratorTest`:mock `ChatModelFactory`,3 case (正常/think 块/元话语) + 空返回 + 超长截断
  - `ResidentInteractionJobTest`:mock 所有依赖,掷骰子用 seeded random 让结果可预测
  - `AuthorReplyListenerTest`:mock listener + 调高概率到 1.0,断言触发
  - `ZhipuMediaClientTest`:mock HttpClient,断言 body 字段
- **集成测**:
  - 不写—— 任务跑 24h 后看 DB 即可。集成度太深,投入产出比差。

## 风险 / 降级

| 风险 | 降级 |
|------|------|
| LLM 雪崩 | 每 tick 最多 3 帖 × 2 agent × 1 次 LLM = 6 次,即便全失败也只 log |
| 评论复读 | LLM prompt 强约束 + 单测断言 5 个 agent × 5 个帖 = 25 条不重样(肉眼) |
| 调度重叠 | `fixedDelay` 模式天然防重叠(上一轮没完下一轮不开始) |
| 智谱速率 | 限速在 `ZhipuMediaClient` 已有指数退避,本次不动 |
| Agent 离线 | `findByStatus('active')` 过滤掉 inactive 即可 |

## 验收

1. `mvn -DskipTests=false test` 全绿
2. 重启 app,等 1h,DB `agent_comment` 表新增行数 >= 5
3. 同一作者 5min 内不会被回复 2 次(cooldown 起效)
4. 故意改坏 `watermark_enabled:false` → `ZhipuMediaClientTest` 挂
