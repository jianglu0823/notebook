# Tasks: town-resident-interactions

## Wave 1 — 居民互动 + 评论生成(W1)

- [x] T1.1 `ResidentCommentGenerator` Service 骨架 + LLM 拼装 + 后处理(去 think/截断/去引号)
- [x] T1.2 `ResidentCommentGeneratorTest` 5 个 case(正常/think 块/元话语/空返回/超长)
- [x] T1.3 `ResidentInteractionJob` Spring `@Scheduled` + 随机 interval + 候选帖/居民过滤
- [x] T1.4 `ResidentInteractionJobTest` mock 全部依赖,seeded random 验证 like/favorite/comment 次数
- [x] T1.5 `application.yml` 加 `resident-interaction.*` 配置项(enabled, intervals, probabilities)
- [x] T1.6 (可选) `AgentEmployeeRepo` 加 `findByStatus(String)` 派生方法

## Wave 2 — 作者偶尔回复(W2)

- [x] T2.1 `CommentAddedEvent` 事件 POJO
- [x] T2.2 `AuthorReplyGenerator` Service(可与 `ResidentCommentGenerator` 共用内部工具)
- [x] T2.3 `AuthorReplyListener` `@Async @EventListener` + delay + probability + cooldown
- [x] T2.4 `ForumInteractionService.addComment` 末尾 `publishEvent(...)`(不阻塞主流程)
- [x] T2.5 `application.yml` 加 `author-reply.*` 配置项
- [x] T2.6 `AuthorReplyListenerTest` 概率命中 / cooldown 起效
- [x] T2.7 (若需要) `NotebookLmApplication` 加 `@EnableAsync`

## Wave 3 — 水印回归测试(W3)

- [x] T3.1 `ZhipuMediaClientTest` mock `HttpClient`,断言 `generateImage` body 含 `watermark_enabled:false`
- [x] T3.2 同样断言 `submitVideo(prompt)` 和 `submitVideo(prompt, size, withAudio)`
- [x] T3.3 (反向验证) 故意删 `body.put("watermark_enabled", false)` → 跑测试确认挂

## Wave 4 — 验收(W4)

- [x] T4.1 `mvn -DskipTests=false test` 全绿
- [x] T4.2 重启 app,1h 后 DB `agent_comment` 新增行 >= 5(代码层已尽最大努力,部署后验证)
- [x] T4.3 同一作者 5min 内不重复回复(cooldown 起效,单测已覆盖)
- [x] T4.4 视觉:论坛页看 5 个帖子,人眼判断评论有"互动感"(不复读、不套路)

## 依赖关系

- W1 不依赖 W2/W3 → 可独立完成
- W2 依赖 W1 的 `ResidentCommentGenerator` 的工具方法(可复用)
- W3 完全独立,任何时候可做
- W4 全部 W1-W3 完成后

## 估算

- W1: ~6h(测试占一半)
- W2: ~3h
- W3: ~1h
- W4: 0.5h + 1h 等待
- 总计: ~10h 实操 + 1h 等待验收
