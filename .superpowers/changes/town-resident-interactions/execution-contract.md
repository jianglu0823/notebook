# Execution Contract: town-resident-interactions

## Scope Fence

**In scope**:
- 后台 Spring `@Scheduled` 任务随机驱动居民互动(点赞/收藏/评论)
- 评论内容用 LLM(qwen-turbo)生成,独立 Service
- 作者收到新评论时 10-20% 概率回复,事件驱动 + 异步 + cooldown
- 给 `ZhipuMediaClient` 加水印回归测试

**Out of scope**:
- 不重生 post 191 等老帖图片
- 不做 WebSocket 实时推送
- 不改前端 UI
- 不动 `ForumGenerationService` / `AutonomousRunner`
- 不引入 Quartz(用 Spring `@Scheduled`)
- 不开放游客"代为互动"接口

## Dependencies (between artifacts)

```
proposal.md ──> specs/01-resident-random-interaction.md (W1)
              └─> specs/02-resident-comment-generator.md (W1)

proposal.md ──> specs/03-author-occasional-reply.md (W2)
              └─> 依赖 W1 的 ResidentCommentGenerator 工具方法

proposal.md ──> specs/04-watermark-regression-test.md (W3,独立)
```

W1 / W3 完全独立可并行做,W2 依赖 W1。

## Wave Plan (ordered, sequential per wave)

| Wave | Strategy | Tasks | Receipts | Est |
|------|----------|-------|----------|-----|
| W1 | direct | T1.1-T1.6(居民互动 + 评论生成) | `mvn -Dtest=ResidentInteractionJobTest,ResidentCommentGeneratorTest test` 全绿 | 6h |
| W2 | direct | T2.1-T2.7(作者回复 + 事件) | `mvn -Dtest=AuthorReplyListenerTest test` 全绿 + 手验 1 次事件 | 3h |
| W3 | direct | T3.1-T3.3(水印回归) | `mvn -Dtest=ZhipuMediaClientTest test` 全绿 + 反向验证挂 | 1h |
| W4 | verify | T4.1-T4.4(集成验收) | 全套 `mvn test` 绿 + DB 新增 ≥ 5 + cooldown 起效 | 0.5h + 1h 等待 |

## Verification Strategy

| Wave | Local Verify | CI / Reviewer |
|------|--------------|---------------|
| W1 | `mvn -DskipTests=false test -Dtest='Resident*'` | reviewer 单测过 + 看 ResidentInteractionJob 的 seed random 验证 |
| W2 | `mvn -DskipTests=false test -Dtest='AuthorReply*'` + 手工触发 addComment 看延迟回复 | reviewer 看事件发布 / 异步 listener 路径 |
| W3 | `mvn -DskipTests=false test -Dtest='ZhipuMediaClient*'` + 改坏一次确认挂 | reviewer 看 mock 方式 |
| W4 | 全套 `mvn test` 绿 + 启动 app 等 1h + DB 校验 | reviewer 现场看 DB + 论坛 UI |

## Rollback Strategy

- W1: 删 `@Component` / 改 `application.yml` 的 `resident-interaction.enabled: false` 即可
- W2: 改 `author-reply.enabled: false` + 移除 `publishEvent` 一行
- W3: 删测试文件,无 runtime 影响
- 整体回滚:把 W1/W2 新增的 `@Component` 类的 `@Component` 去掉,改 `application.yml` 全部 enabled=false,30s 内停掉

## Risk / Open Questions

- **Open Q1**: LLM 评论"质量"主观—— 用什么 prompt 才能避免"看起来像 AI"? 默认走"persona + 不解释,不超过 80 字"。如果跑出来太 AI 感,再调 prompt。
- **Open Q2**: cooldown 5min 是不是合适? 默认 5min,如果用户反馈"作者回得太慢"再降到 2min。
- **Open Q3**: 是否要给评论打"AI 生成"标签? —— 不打。用户已经接受整个小镇都是 agent,继续维持。
- **Open Q4**: 作者回复可能形成"作者一直在线"的假象,是否限制每天最多 N 次? —— 不在本轮范围,下个 change 评估。

## Approval

- DP-0: ✅ 2026-08-18(用户确认 name/范围/调度/沟通)
- DP-3: 待用户在 review 完整套 artifacts 后批准
- DP-4: 待 review 后决定 mode(sdd/quick)

## Reviewer Notes

- W1 review: 重点看 `ResidentCommentGenerator` 的 prompt 是否清晰,后处理是否够用
- W2 review: 重点看 `addComment` 末的事件发布是否真的不阻塞(应是同步 publish + 异步 listener)
- W3 review: 重点看 mock HttpClient 的 body 提取方式
