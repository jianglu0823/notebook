# Design: AI 伴侣聊天机器人（companion-chat）

## Context

### 现状
- **现有能力**:`LLM-note` 提供 QA 问答(SSE 流式)、播客(脚本+TTS+ffmpeg)、小红书工作台(分段视频+cosyvoice+ffmpeg)、智能体小镇(LLM 驱动的群体模拟)。所有聊天/对话能力都是**任务型**或**群体模拟**，缺单一角色持续陪伴。
- **LLM 工厂**:`io.llmnote.llm.ChatModelFactory`(`ChatModelFactory.java:42`)已合并 GLM 免费 + Qwen 付费模型列表，提供 `pricing()`/`normalize()`/`forModel()`/`streamText()`/`streamTextWithFallback()`；`reasoningModel()` 默认 `glm-z1-flash`。
- **ASR 客户端**:`io.llmnote.voice.VoiceWsHandler`(`VoiceWsHandler.java:32`)用 DashScope `paraformer-realtime-v2` 做流式 PCM16/16k 转写；`VoiceHandshakeInterceptor` 做 WebSocket 鉴权握手；只在 `text`/`status`/`error` 三类控制帧+二进制 PCM 间转换，不调 LLM。
- **TTS**:`io.llmnote.studio.XhsService.ttsToFile`(`XhsService.java:628`)用 `com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer`(`cosyvoice-v1` + `longwan`/`longcheng` 音色)合成 MP3 落盘；**目前只有 file-based 模式**，无 streaming。
- **视觉**:`NotebookLmProperties.zhipu.vlModel=glm-4.6v-flash` + `dashscope.vlModel=qwen-vl-max` 已配置。
- **媒体落盘**:`io.llmnote.llm.ZhipuMediaClient.downloadToWorld`(`ZhipuMediaClient.java:143`)有下载 URL 到 `imageDir/<sub>/<file>` 的工具。
- **Milvus**:已在 `io.llmnote.config.AgentScopeConfig`(`AgentScopeConfig.java:26`)装配 `MilvusStore`，collection 默认 `nblm_chunks`，dim 1024，复用 `text-embedding-v3`。
- **鉴权**:`io.llmnote.auth.AuthFilter` 处理 cookie/JWT；`VoiceHandshakeInterceptor` 在握手阶段注入 `userId`。
- **数据库**:`ddl-auto:none`，所有新表/列需手工 ALTER；既有 DDL 在 `infra/mysql/init.sql`；既有增量在 `notebooklm-app/changes/<name>/migration.sql`。
- **前端**:`notebooklm-app/src/main/resources/static/index.html` 单文件 SPA，顶部 tab 切视图（#worldView、#qaView、#podcastView、#studioView 等），既有 tab 与 fetch/render 模式可复用。
- **WebSocket**:`io.llmnote.voice.WebSocketConfig` 注册 `/ws/voice`（已存在）；新路径 `/ws/companion` 同端口并行。

### 约束
- **`ddl-auto:none`**:5 张新表需手工 ALTER；`infra/mysql/init.sql` 同步追加基线；交付 `changes/companion-chat/migration.sql` 让用户执行。
- **既有代码零侵入**:`ChatModelFactory`/`AuthFilter`/`VoiceWsHandler`/`ZhipuMediaClient` 不改；如需扩 handshake 鉴权方式，复制 `VoiceHandshakeInterceptor` 为 `CompanionHandshakeInterceptor` 而非改原文件。
- **TTS 限制**:DashScope `SpeechSynthesizer` 当前只支持 file-based 同步调用，**流式 TTS 需自实现**：按句切分 LLM 文本 → 每句调一次 `SpeechSynthesizer.call(...)` 落临时文件 → 读 MP3 字节推 WebSocket binary 帧。
- **前端解码**:浏览器 `<audio src=blob:...>` 直接播 MP3 无延迟问题但不支持流拼接；用 `AudioContext.decodeAudioData(mp3Bytes)` + `AudioBufferSourceNode` 顺序排队播放。
- **Milvus 隔离**:`nblm_chunks` 是 RAG 检索用，**伴侣记忆必须独立 collection**（`companion_memories`），避免 payload 字段冲突和检索语义混淆。
- **LLM 限流**:`glm-z1-flash`/`glm-4.7-flash` 都是免费模型，限流敏感；记忆抽取任务必须走 `streamTextWithFallback` 降级链。
- **多模态视觉模型**:免费 `glm-4.6v-flash` 是首选（已配），仅当与已选文本模型不匹配（如用户选 qwen）才切 `qwen-vl-max`。

### Stakeholders
- 用户（聊天方，需要被陪伴、定制伴侣、记得自己）。
- 后端服务（ChatModelFactory、ASR、TTS、Milvus、MySQL）。
- 浏览器前端（录音、播音、UI 交互）。
- 平台方（合规审计：AI 标识、用时提醒、心理安全）。

## Goals
1. 用户可**创建多个伴侣**（5/10 字段 + 12 模板），CRUD 流畅、跨用户隔离。
2. 用户可与任一伴侣**流式文字聊天**（打字机），LLM 流式 token 实时推送。
3. 用户可**发送图片**并得到视觉理解回复。
4. 用户可**语音消息**录 → ASR → LLM → TTS 完整链路播报。
5. 长期记忆使伴侣"记得"用户（Milvus 向量检索 + 实时抽取）。
6. 亲密度计数 + 里程碑提示让用户感知关系成长。
7. 最小合规：AI 标识 banner + 2h 用时提醒 + 心理援助提示。
8. 既有功能零回归；`mvn -o clean compile`/`mvn -o test` 全绿。

## Decisions

### D1. 包结构：新增 `io.llmnote.companion.*`，与现有 world/studio/voice 并列
**Choice**:`io.llmnote.companion` 包下分 `entity` / `repository` / `service` / `controller` / `dto` / `ws` / `memory`。
**Rationale**:与 `world/`/`studio/`/`voice/` 风格一致；新包便于 git diff 关注。
**Alternatives**:放 `io.llmnote.studio` 下(否决:业务域不同，会污染现有 studio 的写作流)。

### D2. WebSocket handler 独立 `CompanionWsHandler`，不复用 `VoiceWsHandler`
**Choice**:新建 `io.llmnote.companion.ws.CompanionWsHandler`，继承 `TextWebSocketHandler`（不是 `BinaryWebSocketHandler`，因为伴侣 WS 协议是 JSON+binary 混合）；`WebSocketConfig` 注册 `/ws/companion` → CompanionWsHandler；握手鉴权复制 `VoiceHandshakeInterceptor` 为 `CompanionHandshakeInterceptor`。
**Rationale**:`VoiceWsHandler` 协议极简（只 text/status），伴侣协议复杂得多（chat/voice/image/audio/done 多类型）。继承/扩展会污染原 voice 包的代码。
**Alternatives**:扩展 `VoiceWsHandler` 加 type 分支(否决:职责混杂；voice 用户的 ASR 协议不能改)。

### D3. chat 协议走 WebSocket（不复用 SSE）
**Choice**:WebSocket 全双工满足 LLM 流式 token + 语音二进制 + 文本 JSON 控制 + TTS 音频回推。SSE 只能单向推流且无法承载二进制音频。
**Rationale**:用户已选 WebSocket 复用 voice 通道（DP-0 已确认）；voice 通道在 `ws://<host>/ws/voice`，新通道在 `ws://<host>/ws/companion`，同端口多 path 不冲突。
**Alternatives**:SSE 仅文字 + REST 单独推 TTS(否决:协议碎,前端要维护两连接)。

### D4. 文字流式 LLM 调用 = `ChatModelFactory.streamText`/`streamTextWithFallback`
**Choice**:`CompanionChatService` 收到 chat.text 后，构造 `List<Msg>`（system + 记忆 + 历史 user/assistant），调 `factory.streamTextWithFallback(model, msgs)`，遍历返回的 `List<ChatResponse>` 逐 chunk 推 `chat.delta`；完整 `fullText` 落 `companion_message` 行。
**Rationale**:与现有所有 LLM 调用一致(参见 `WorldSimEngine.java`/`WritingService`/`XhsService`)，降级链自动启用。
**Alternatives**:直接调 `model.stream(messages)`(否决:失去限流重试与降级)。

### D5. 长期记忆：MySQL `companion_memory` + Milvus `companion_memories` 双写
**Choice**:
- `companion_memory` 表存记忆元数据（id/companion_id/user_id/content/source_message_id/created_at/deleted）。
- Milvus `companion_memories` collection（独立于 `nblm_chunks`，dim 1024）存向量，payload 含 `companion_id/user_id/memory_id/content`。
- 抽取走 `ChatModelFactory.reasoningModel()`（`glm-z1-flash`）调 `streamTextWithFallback`；prompt 固定中文；输出按行解析为 0~3 条 fact。
- 检索 top-K=5，按 `companion_id` 与 `user_id` 双过滤（避免跨用户命中）。
**Rationale**:用户已选"实时提取+向量检索"（DP-0）。MySQL 存元数据便于"记忆管理"CRUD，Milvus 存向量用于相似度检索；二者通过 `memory_id` 关联，删除时双删。
**Alternatives**:只存 Milvus(否决:无法做"管理 UI"列表/删除元数据)；用 PG pgvector(否决:项目用 MySQL+Milvus，引入 PG 改动过大)。

### D6. 记忆抽取 = 异步 + 失败不阻塞
**Choice**:每轮 chat 流式结束后，`CompletableFuture.runAsync` 提交抽取任务；任务内调 LLM → 解析 → 写 MySQL → 写 Milvus，任一失败记 warn 不抛回。
**Rationale**:聊天实时性优先；记忆抽取 ~1~3s 延迟用户无感；失败可后续重试或放弃。
**Alternatives**:同步抽取(否决:每轮多等 2~3s 严重降级体验)。

### D7. TTS 流式 = 按句切分 + SpeechSynthesizer 同步调用 + binary 帧推流
**Choice**:
- LLM 完整 `fullText` 落库后，按中文/英文/日文句末标点 `[。！？!?\n]` 切分为 N 句。
- 对每句调 `SpeechSynthesizer.call(new SpeechSynthesisParam().text(sentence).voice(voice).format(MP3_22050HZ_MONO_256KBPS))` 得到 MP3 字节。
- 将 MP3 字节直接作为 WebSocket binary 帧推 `chat.audio`；每帧前 4 字节为 `seq`（int 大端），之后为 MP3 字节。
- 前端用 `AudioContext.decodeAudioData(mp3Bytes)` 解码后 `AudioBufferSourceNode` 顺序排队播放。
**Rationale**:`SpeechSynthesizer` SDK 没有流式接口，按句调用是"伪流式"——首句出音频 ~1s（已含 TTS 调用时延），后续句错位但每句独立 mp3 可拼。避免落盘中间文件。
**Alternatives**:整段一次性 TTS 落盘再返回(否决:无流式感)。

### D8. ASR 复用 DashScope paraformer-realtime-v2，复制 VoiceWsHandler 的 Recognition 包装模式
**Choice**:`CompanionWsHandler.handleTextMessage` 解析 `voice.start`/`voice.stop`；`handleBinaryMessage` 推 PCM 字节到 `Recognition.sendAudioFrame`；同 voice 包的实现。**不复用** `VoiceWsHandler` 实例（每个 WS session 独立 Recognition）。
**Rationale**:避免 WS 协议跨 handler 复用；同款 SDK、同款调用。
**Alternatives**:直接 inject `VoiceWsHandler`(否决:API 协议是 voice 专属，与伴侣协议不匹配)。

### D9. 模型下拉 = `ChatModelFactory.pricing()`，前端聊天时临时覆盖
**Choice**:GET `/api/companion/models` 返回 `pricing()` 全量；chat.text 帧 `model` 字段优先级 > 伴侣 `defaultModel` > `defaultTextModel()`；都过 `normalize()`。
**Rationale**:零侵入 ChatModelFactory；与现有"模型选择"心智模型一致（参考 `WritingService`/`XhsService` 的 model 参数）。
**Alternatives**:伴侣不存 defaultModel,每次都让用户选(否决:多一次点击)。

### D10. 视觉模型 = 伴侣 `defaultModel` 决定，不用 `voiceStyle`/其他维度
**Choice**:当 chat.text 带图片时，按伴侣 `defaultModel` 走：
- 若 `defaultModel` 以 `glm-` 开头 → `glm-4.6v-flash`。
- 若以 `qwen-` 开头 → `qwen-vl-max`。
- chat.text 临时 `model` 字段覆盖文本模型选择但**不**覆盖视觉模型（视觉模型总是跟随伴侣默认模型所属派系），保持简单。
**Rationale**:与"用户选 GLM/qwen 派系"心智一致；不引入额外配置。
**Alternatives**:伴侣增加 `visionModel` 字段(否决:增加表单复杂度，本期收益小)。

### D11. 亲密度 = 规则化计数，零 LLM
**Choice**:`CompanionIntimacyService.recordInteraction(companionId)`：
- 读 `companion_intimacy` 行（或新建）。
- `score += min(1, 50 - todayCount)`；`todayCount` 由当日 `companion_message` 聚合得；日上限 50。
- 跨日 `daysCount += 1`。
- 检查 milestone，触发追加 system 消息行。
**Rationale**:零 LLM 成本/零限流风险；规则可测可调。
**Alternatives**:LLM 判断"今天用户表达了什么情绪决定 +1~+3"(否决:成本爆炸，spec 无要求)。

### D12. 前端单页新增 `#companionView` Tab，复用既有 fetch/render 风格
**Choice**:顶部 tab 加 "伴侣" 项；点击切到 `#companionView`；内部左侧伴侣列表 `<aside>` / 中间聊天区 `<main>` / 右侧配置抽屉 `<aside class="drawer">`（默认隐藏，点击伴侣名/设置按钮展开）。
**Rationale**:与既有 #worldView 风格一致；既有 DOM/JS 不动。
**Alternatives**:重写整个 index.html(否决:回归风险高)。

### D13. 合规实现 = 前端独立 + system prompt 注入
**Choice**:
- AI 标识 banner:固定 HTML 元素，CSS 强制 `position:sticky; top:0; z-index:10`。
- 2h 用时提醒:`setInterval` 每分钟 +1，达到 120 弹窗；切走视图调 `clearInterval`，切回重启。
- 心理援助提示:前端敏感词正则 `/(自杀|自残|不想活|轻生|想死)/`，匹配时显示提示。
- system prompt 注入:在 `CompanionChatService.buildSystemPrompt` 最前固定拼"你是一个 AI 角色..."。
**Rationale**:合规要点最小实现，不引入内容审核服务（PRD V2.0 范围）。
**Alternatives**:接入第三方内容审核 API(否决:超范围)。

## 新增数据表汇总（手工 ALTER，交付 SQL）

| 表 | 关键列 | 索引 | 用途 |
|---|---|---|---|
| `companion` | id, user_id, name, gender, age, personality_tags, bio, voice_style, relationship_type, intimacy_start, speaking_style, background_story, default_model, status, intimacy_score, last_message_at, created_at, updated_at | (user_id, status) | 伴侣元数据 |
| `companion_message` | id, companion_id, role, content, attachments_json, metadata_json, hidden, created_at | (companion_id, id), (companion_id, created_at) | 消息流水 |
| `companion_memory` | id, companion_id, user_id, content, source_message_id, deleted, created_at | (companion_id, deleted) | 长期记忆元数据 |
| `companion_template` | template_key PK, name, gender, age, personality_tags, voice_style, relationship_type, intimacy_start, speaking_style, background_story, default_model | — | 12 预设模板种子 |
| `companion_intimacy` | companion_id PK, score, days_count, last_interact_date, milestone_7_sent, milestone_30_sent, milestone_100_sent | — | 亲密度 + 里程碑 |

Milvus collection:`companion_memories`(dim=1024, metric_type=IP, payload fields=companion_id/user_id/memory_id/content)。

## Risks And Trade-Offs
- **TTS 流式实现复杂**:SpeechSynthesizer 同步调用按句切分伪流式，首句 ~1s 时延不可消除（SDK 限制）。缓解：前端在 LLM 文字流式末尾立刻开始放第一句 TTS，视觉/听觉重叠缓冲。
- **ASR 占用带宽**:同一用户可能同时连 `/ws/voice`(笔记录音) 和 `/ws/companion`(伴侣聊天)，各起一条 WebSocket；浏览器无限制但带宽翻倍。缓解：UI 上明确"伴侣视图独占"提示，避免同时启用。
- **记忆抽取限流**:每轮对话都调一次 LLM 抽记忆，免费模型限流敏感。缓解：走 `streamTextWithFallback` 降级链；输出 NONE 时快速返回；失败重试上限 2 次。
- **Milvus 写入失败**:抽取成功后写 Milvus 失败 → 记忆"半成功"（MySQL 有但向量无，下次检索不到）。缓解：先写 Milvus 再写 MySQL；写 MySQL 失败时回滚 Milvus 删；记 error 日志便于补偿。
- **前端单文件膨胀**:`index.html` 加 ~1000 行伴侣视图代码；既有 SPA 框架未引入，文件本就单页。缓解：把伴侣视图封装成 IIFE 命名空间 `window.CompanionView = {...}`，避免全局变量污染。
- **手工迁移一致性**:5 表 ALTER + Milvus collection 创建需用户执行；`infra/mysql/init.sql` 同步追加；提供 `migration.sql` 一次性脚本。
- **跨用户隔离**:`companion_message`/`companion_memory` 必须严格按 `user_id` 过滤；Repository 方法签名强制 `findByUserIdAndCompanionId` 而非 `findByCompanionId`。
