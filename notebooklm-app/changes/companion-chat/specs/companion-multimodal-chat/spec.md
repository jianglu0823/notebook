# Spec: companion-multimodal-chat

伴侣多模态聊天：文字流式聊天、图片识别、语音消息（ASR→LLM→TTS 流式推回）。客户端通过 WebSocket 与后端保持长连接，按 JSON 控制帧 + 二进制音频帧交互。

## ADDED Requirements

### Requirement: 文字流式聊天

系统 SHALL 提供 WebSocket 端点 `/ws/companion`（鉴权沿用 `VoiceHandshakeInterceptor` 模式），协议：客户端发 `{"type":"chat.text","companionId":N,"text":"...","model":"glm-4.7-flash","requestId":"..."}`，后端在 200ms 内回 `{"type":"ack","requestId":"..."}` 确认；之后按 LLM 流式 token 逐 chunk 推 `{"type":"chat.delta","requestId":"...","delta":"..."}`；LLM 流结束推 `{"type":"chat.done","requestId":"...","fullText":"...","messageId":N,"durationMs":N,"usage":{...}}`。模型名 MUST 通过 `ChatModelFactory.normalize()` 校验。

#### Scenario: 文字消息流式返回
- WHEN 客户端发 chat.text 携带文本"你好"
- THEN 在合理时延内收到 N 个 chat.delta 帧（拼成完整回复）+ 1 个 chat.done 帧（含 messageId）
- AND `companion_message` 新增一行 `role=assistant, content=完整回复, companion_id=N`

#### Scenario: 未知模型名走默认
- WHEN 客户端 chat.text 携带 `model="unknown-model"`
- THEN 后端用 `ChatModelFactory.normalize()` 校正为 `glm-4.7-flash`（默认）并继续

#### Scenario: WebSocket 未鉴权被拒
- WHEN 客户端未带有效 JWT cookie / header 连 `/ws/companion`
- THEN 握手失败（状态码由 VoiceHandshakeInterceptor 决定）

### Requirement: 图片识别

POST `/api/companion/chat/upload` SHALL 接受 multipart 单文件（image/png|jpeg|webp，≤10MB），落 `uploads/companion/<yyyy-MM-dd>/<uuid>.<ext>`，返回 `{url, messageId, content:"[图片]"}`。客户端在 chat.text 帧的 `attachments` 数组携带已上传 `url`，后端 MUST 按伴侣的 `defaultModel` 选用视觉模型（`glm-4.6v-flash` / `qwen-vl-max`）调 LLM，文本回复中包含图片理解结果。

#### Scenario: 上传图片成功
- WHEN 用户 POST 携带 1.2MB PNG
- THEN 201 返回 `{url:"/api/companion/file/<uuid>.png"}`
- AND 落 `uploads/companion/<date>/<uuid>.png`

#### Scenario: 图片超 10MB 被拒
- WHEN 用户 POST 携带 11MB 文件
- THEN 返回 413

#### Scenario: 聊天携带图片
- WHEN 客户端 chat.text 携带 `attachments:[{url:"...png",kind:"image"}]`
- THEN 后端用视觉模型；回复内容包含图片理解
- AND 消息流是 chat.delta + chat.done 序列（流式不变）

### Requirement: 语音消息 ASR→LLM→TTS

WebSocket 协议 MUST 支持完整语音消息链路：客户端发 `{"type":"voice.start","companionId":N,"requestId":"..."}` → 后端回 `{"type":"voice.ready","requestId":"..."}` → 客户端推 PCM16/16kHz 二进制帧（持续）→ 客户端发 `{"type":"voice.stop","requestId":"..."}` → 后端回 `{"type":"voice.text","requestId":"...","text":"转写结果","sentenceEnd":true}` → 客户端发 `{"type":"chat.text","companionId":N,"text":"<转写结果>","requestId":"..."}` → 后端走 chat 流式逻辑 + 额外在 chat 流结束后按句切分文本，每句调 DashScope `cosyvoice-v1`（音色按伴侣 `voiceStyle` 选 `longwan`/`longcheng`/其他）合成 MP3 → 每句 MP3 帧作为 WebSocket 二进制帧推 `{"type":"chat.audio","requestId":"...","seq":K,"format":"mp3"}`（前 4 字节为 audio header JSON 头或简化为 binary 头 + 后续 MP3）→ 所有句合成完成推 `{"type":"chat.audio.done","requestId":"..."}`。

#### Scenario: 完整语音消息链路
- WHEN 客户端完成 voice.start → 二进制帧 → voice.stop → chat.text 流程
- THEN 用户消息先以 ASR 转写文本出现；LLM 文字回复流式；之后收到 N 个 chat.audio 二进制帧（每句一帧）
- AND `companion_message` 至少 2 行：user 行的 `metadata.audioUrl` 存录音落盘路径；assistant 行的 `metadata.ttsFiles` 存每句 MP3 路径

#### Scenario: ASR 转写失败降级
- WHEN ASR 调用因网络异常失败
- THEN 后端回 `{"type":"voice.error","requestId":"...","reason":"asr-failed"}`
- AND 客户端可重试或退回文字输入

#### Scenario: TTS 失败不阻断文字
- WHEN 某句 TTS 合成失败
- THEN 该句不推 audio 帧，记 warn 日志；后续句继续；最终 chat.audio.done 仍推
- AND 文字 chat.delta/chat.done 不受影响

### Requirement: 消息历史

GET `/api/companion/{id}/messages?beforeId=N&limit=50` MUST 返回该伴侣的消息列表（按 `id DESC`），只含 `hidden=false` 的消息，任一不存在/不属于当前用户的伴侣 ID MUST 返回 404。响应体每项 MUST 含 `id/role/content/createdAt/attachments/metadata`。

#### Scenario: 拉取最近 50 条
- WHEN 用户 GET 不带 beforeId
- THEN 返回最近 50 条按 id 降序

#### Scenario: 增量拉取
- WHEN 用户 GET 携带 `beforeId=200`
- THEN 返回 id<200 的最近 50 条

### Requirement: 消息操作

PATCH `/api/companion/message/{id}` SHALL 支持 `action: "delete"`（软删：`hidden=true`）和 `action: "regenerate"`（删除原 assistant 消息，重新调 LLM 流式生成新消息，行为同 chat.text）。任一不存在的消息或不属于当前用户的伴侣 MUST 返回 404。

#### Scenario: 软删消息
- WHEN 用户 PATCH 携带 `action:"delete"`
- THEN 204；该消息后续 GET 列表不返回

#### Scenario: 重新生成
- WHEN 用户 PATCH 携带 `action:"regenerate"` 指向 assistant 消息
- THEN 该 assistant 消息 `hidden=true`；新生成一条 assistant 消息（含流式 chat.delta + chat.done）
- AND `companion.lastMessageAt=now`
