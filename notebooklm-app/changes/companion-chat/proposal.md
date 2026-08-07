# Proposal: AI 伴侣聊天机器人（companion-chat）

## Why

`LLM-note` 当前的能力都围绕"笔记/知识/创作"展开：QA 问答、播客、小红书工作台、智能体小镇——都是**任务型**或**群体模拟**交互，缺少与单一角色**持续陪伴式对话**的产品形态。`PRD:🤖 AI 伴侣聊天机器人` 已定义完整愿景（V1.0~V3.0），但其全部能力（实时语音通话、音色克隆、订阅、社区、本地模型、API 开放等）远超本项目现阶段可承受范围。

本变更先把 **PRD V1.0 + V1.5 主体能力 + 亲密度基础**落地到一个可用版本：

- 用户可**创建多个伴侣**（5 字段快速建 + 10 字段高级定制 + 12 个预设模板），每个伴侣有独立名字/性别/性格/系统提示词/默认模型。
- 用户可与任一伴侣**流式文字聊天**（打字机效果）、**发送图片由多模态模型理解**、**录制语音由 ASR 转写为文字进 LLM**，LLM 文本回复可**由 TTS 流式推回浏览器播报**。
- 用户可**选择底层模型**（沿用现有 `ChatModelFactory.pricing()` 的 GLM 免费系列 + Qwen 付费系列），每个伴侣默认一个、聊天时可临时切换。
- 系统对每段对话**实时抽取"用户偏好/关键事件"写入长期记忆**（Milvus 向量库），下次进该伴侣的 system prompt，使伴侣"记得"用户。
- 维护**亲密度计数**（基础版：消息数 + 互动天数），用户可见。
- 前端**AI 标识 + 2 小时用时提醒** 合规要求最小实现。

## What Changes

1. **伴侣 CRUD**：用户可创建/编辑/删除多个 AI 伴侣；每个伴侣含 5 必填字段（名称、性别、性格标签、年龄、简介/签名）和 10 字段完整定制（声音音色、关系类型、亲密度起点、说话风格、背景故事等），提供 12 个预设模板（温柔学姐/霸道总裁/元气少女/清冷剑仙/毒舌损友/优雅贵妇/全能管家/猫耳女仆/学术导师/摇滚歌手/魔法师/职场前辈）。
2. **多模态流式聊天**：
   - 文字：WebSocket 通道复用 `voice` 包既有握手，推送 LLM 流式 token。
   - 图片：前端 `<input type=file>` 上传到后端 `/api/companion/chat/upload` 落 `uploads/companion/<date>/<uuid>.<ext>`，按伴侣的视觉模型调 `glm-4.6v-flash` / `qwen-vl-max`。
   - 语音消息：浏览器 `MediaRecorder` 推 PCM16 → WebSocket → DashScope `paraformer-realtime-v2` ASR → 文本进 LLM。
   - TTS：LLM 完整回复后按句切片，每句调 DashScope `cosyvoice-v1` 合成 MP3 → WebSocket 二进制帧回推；前端用 `AudioContext` 解码后用 `AudioBufferSourceNode` 排队播放。
3. **模型选择**：复用 `ChatModelFactory.pricing()` 现有模型列表，前端聊天顶部下拉切换；伴侣配置里存"默认模型"，用户每次开聊可临时覆盖。
4. **实时长期记忆**：每轮对话 LLM 回复落库后，**异步**调 LLM 抽取"用户偏好/关键事件"（如"用户叫小王，喜欢猫，2026-08-05 提到要去日本"），写入 `companion_memory` 表 + Milvus `companion_memories` collection；下次聊天时按 `companion_id` 过滤做 top-K 检索，命中片段拼进 system prompt。
5. **基础亲密度**：每条消息 +N（消息数 × 1，每天上限 50），同时记录"和伴侣聊天天数"；前端聊天顶部显示当前亲密度；到达 7/30/100 天时触发系统消息（"我们已经认识 7 天啦～"）。
6. **合规最小实现**：聊天界面顶部固定 banner "AI 角色，生成内容仅供参考"；连续使用 ≥ 2 小时弹窗提醒；对话首条 system prompt 强制包含"你是一个 AI 角色"声明。

## Scope

### In

- 新增 `companion` 业务包：`io.llmnote.companion.*`（entity / repository / service / controller / dto / ws）。
- 新增 WebSocket handler：`CompanionWsHandler`（独立 channel，复用 voice 包的握手/鉴权模式），协议：JSON 控制帧 + PCM 二进制帧 + 音频二进制帧。
- 新增 REST API：`/api/companion/*`（CRUD、聊天上传、历史查询、记忆查询、亲密度查询、模型下拉）。
- 数据库新增 5 张表：`companion`（伴侣元数据）、`companion_message`（消息流水）、`companion_memory`（长期记忆元数据 + Milvus 主键）、`companion_template`（12 预设模板种子）、`companion_intimacy`（亲密度 + 里程碑）。
- Milvus 新增 collection：`companion_memories`（独立于 `nblm_chunks`，dim 沿用 1024）。
- 复用 `ChatModelFactory` 文本/视觉/降级、复用 `VoiceWsHandler` 的 ASR 客户端模式、复用 `ZhipuMediaClient` 的落盘模式、复用 `AuthFilter` 鉴权。
- 前端 `index.html` 新增 `#companionView` 视图：左侧伴侣列表 / 中间聊天区 / 右侧伴侣配置抽屉。
- 数据库迁移文件：`changes/companion-chat/migration.sql`（手工 ALTER）。

### Out

- 实时语音通话（WebRTC、双工、打断）—— 留 V2.0。
- 音色克隆 / 自定义上传音色 —— 留 V2.5。
- AI 形象生成（立绘、动态表情）—— 留 V2.5。
- 订阅付费 / 增值服务 —— 留 V2.0。
- 角色卡社区 / 导入导出 / 模板商城 —— 留 V2.5+。
- 本地模型部署 / 离线模式 —— 留 V3.0。
- API 开放 / 开发者模式 —— 留 V3.0。
- 多语言国际化 —— 留 V3.0。
- 多伴侣群聊 —— 留 V3.0。
- NSFW 内容（年龄验证 + 实名认证）—— 留 V2.0。
- 主动问候（早 8 / 晚 21 推送）—— 留后续。
- 纪念日 / 送礼 / 吃醋等"小情绪"开关 —— 留 V1.5+，本期只占位 UI。
- 情感记忆（亲密度解锁新互动）—— 本期亲密度只做计数展示。
- 对话场景切换 / 快捷指令 / 聊天记录导出 PDF / 全文搜索 —— P2，留后续。
- 真实货币支付 —— 永久不做。
- 改动既有小镇/QA/写作/播客功能 —— 零侵入。

## Impact

- **数据库**（`ddl-auto:none` → 手工 ALTER）：5 张新表 + 1 个 Milvus collection；既有表零修改。
- **后端**：
  - 新增包 `io.llmnote.companion`（预估 12~15 个文件）。
  - `WebSocketConfig` 需注册新路径 `/ws/companion`。
  - `AuthFilter` 需放行 `/api/companion/**`。
  - 无侵入既有 service / controller。
- **前端**：`index.html` 大量新增（聊天区 + 伴侣列表 + 配置抽屉 + WebSocket 客户端 + 媒体录制器 + 模型下拉 + 亲密度显示 + 用时提醒），既有视图零回归。
- **依赖**：`pom.xml` 需新增 DashScope ASR/TTS SDK（已通过 `XhsService` 间接使用，应已在 dependencyManagement）；Milvus 客户端（已有）。
- **风险**：
  - 实时抽取记忆会**额外消耗 token**（每轮 ~200 token 摘要）；用 `glm-z1-flash` 推理模型走降级链，限流时降级到 `glm-4.7-flash`。
  - TTS 流式推音频帧到 WebSocket，**MP3 帧解码**对前端 `<audio>` 兼容性差；需前端用 `AudioContext.decodeAudioData` 解码 mp3 后用 `AudioBufferSourceNode` 排队播放。
  - ASR WebSocket 复用 voice 协议但不同 path，**两个 handler 并存**——需确认端口不冲突。
  - 模型下拉与 ChatModelFactory 的 `normalize()` 严格一致（避免用户选错模型名）。

## Capabilities

- `companion-crud`：伴侣创建/编辑/删除、12 预设模板、5 字段快速 + 10 字段完整定制。
- `companion-multimodal-chat`：文字流式聊天 + 图片识别 + 语音消息（ASR→LLM→TTS 流式推回）。
- `companion-model-switch`：每伴侣默认模型 + 聊天时临时切换模型（沿用 `ChatModelFactory.pricing()`）。
- `companion-long-term-memory`：实时抽取用户偏好/关键事件 → 写 `companion_memory` 表 + Milvus → 下次对话 top-K 检索进 system prompt。
- `companion-intimacy`：基础亲密度计数 + 7/30/100 天里程碑提示。
- `companion-compliance-ui`：AI 标识 banner + 2 小时用时提醒 + system prompt 强制"我是 AI"。
