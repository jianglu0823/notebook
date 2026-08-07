# Execution Contract: AI 伴侣聊天机器人（companion-chat）

> 单一执行握手。以本契约为准绳实现；偏离范围/需求须回退规划（spec-writer）并刷新本契约。

## Intent Lock

**问题**：`LLM-note` 现有能力都是任务型（QA 问答、播客）或群体模拟（智能体小镇），缺单一角色持续陪伴式对话产品。PRD 定义了完整 V1.0~V3.0 愿景，但全部能力（实时语音通话、音色克隆、订阅、社区、本地模型、API 开放等）远超现阶段可承受范围。

**范围**：把 PRD V1.0 + V1.5 主体能力 + 亲密度基础 落地到 LLM-note 本期。**6 个能力**：

- `companion-crud`：伴侣创建/编辑/删除、12 预设模板、5 字段快速 + 10 字段完整定制、跨用户隔离。
- `companion-multimodal-chat`：文字流式聊天（SSE/WS）+ 图片识别 + 语音消息（ASR→LLM→TTS 流式推回）。
- `companion-model-switch`：每伴侣默认模型 + 聊天时临时切换模型（沿用 `ChatModelFactory.pricing()`）。
- `companion-long-term-memory`：实时抽取用户偏好/关键事件 → 写 `companion_memory` + Milvus → 下次对话 top-K=5 检索进 system prompt。
- `companion-intimacy`：基础亲密度计数（日上限 +50）+ 7/30/100 天里程碑提示。
- `companion-compliance-ui`：AI 标识 banner + 2h 用时提醒 + 心理安全敏感词 + system prompt 强制"我是 AI"。

## Scope Fence（Out of Scope — 越界即回退规划）

- 实时语音通话（WebRTC、双工、打断）—— 留 V2.0。
- 音色克隆 / 自定义上传音色 —— 留 V2.5。
- AI 形象生成（立绘、动态表情）—— 留 V2.5。
- 订阅付费 / 增值服务（音色克隆包、形象生成包、加油包）—— 留 V2.0。
- 角色卡社区 / 导入导出 / 模板商城 —— 留 V2.5+。
- 本地模型部署 / 离线模式 —— 留 V3.0。
- API 开放 / 开发者模式 —— 留 V3.0。
- 多语言国际化 —— 留 V3.0。
- 多伴侣群聊 —— 留 V3.0。
- NSFW 内容（年龄验证 + 实名认证）—— 留 V2.0。
- 主动问候（早 8 / 晚 21 推送）—— 留后续。
- 纪念日 / 送礼 / 吃醋等"小情绪"开关 —— 留 V1.5+，本期只占位 UI。
- 情感记忆（亲密度解锁新互动）—— 本期亲密度只做计数展示，不解锁新能力。
- 对话场景切换 / 快捷指令 / 聊天记录导出 PDF / 全文搜索 —— P2，留后续。
- 真实货币支付 —— 永久不做。
- 改动既有小镇/QA/写作/播客功能 —— 零侵入。

## Constraints（来自 design.md Decisions）

- **C1**：新增 `io.llmnote.companion` 包；不复用 `VoiceWsHandler`，独立 `CompanionWsHandler`（继承 `TextWebSocketHandler`）+ `CompanionHandshakeInterceptor`（复制 voice 握手模式）。（D1, D2）
- **C2**：实时响应走 WebSocket（`/ws/companion`），不复用 SSE。WebSocket 协议承载 JSON 控制帧 + PCM 二进制帧 + 音频二进制帧。（D3）
- **C3**：LLM 调用统一走 `ChatModelFactory.streamText` / `streamTextWithFallback`；模型名过 `normalize()`；记忆抽取走 `reasoningModel()`（默认 `glm-z1-flash`）。（D4, D9, D10）
- **C4**：长期记忆 = MySQL `companion_memory` + Milvus `companion_memories` 双写；collection 独立于 `nblm_chunks`，dim=1024，metric=IP；payload 含 `user_id`+`companion_id`，查询双过滤；top-K=5。（D5）
- **C5**：记忆抽取 = 异步（`CompletableFuture.runAsync`），失败不阻塞聊天；NONE 输出不写库。（D6）
- **C6**：TTS = 按句切分（`[。！？!?\n]`）+ DashScope `cosyvoice-v1` 同步调用 + binary 帧推流；单句失败降级，整段不阻断；前端 `AudioContext.decodeAudioData` + `AudioBufferSourceNode` 排队播放。（D7）
- **C7**：ASR = DashScope `paraformer-realtime-v2` 流式（与 voice 包同 SDK，复制 `VoiceWsHandler` 的 Recognition 包装）；不复用 voice handler 实例。（D8）
- **C8**：视觉模型按伴侣 `defaultModel` 派系：GLM → `glm-4.6v-flash`，Qwen → `qwen-vl-max`；chat.text 临时 model 字段不覆盖视觉模型选择。（D10）
- **C9**：亲密度 = 规则化计数（零 LLM）；日上限 +50；首次互动 daysCount=1；7/30/100 天 milestone 一次性追加 system 消息。（D11）
- **C10**：前端单页新增 `#companionView` Tab（既有 #worldView 等 tab 旁边）；既有 DOM/JS 零改动；命名空间 `window.CompanionView = {...}`。（D12）
- **C11**：合规 = 前端独立（banner / 计时 / 敏感词）+ 后端 system prompt 固定前缀"你是一个 AI 角色..."（不可被 backgroundStory 覆盖）。（D13）
- **C12**：ddl-auto:none → 5 新表手工 ALTER；既有表零修改；`infra/mysql/init.sql` 同步追加基线；`migration.sql` 一次性脚本；Milvus collection 初始化交付独立脚本 `init-milvus.sh`。
- **C13**：`AuthFilter` 放行 `/api/companion/**`；跨用户访问返回 404（不暴露存在性）。
- **C14**：所有 `companion_message`/`companion_memory`/`companion_intimacy` 仓库查询必须带 `userId` 过滤，签名上以 `findByUserIdAndXxx` 命名。

## Approved Requirements → Test Obligations → Batch

| # | Requirement（SHALL/MUST） | Test Obligation | Batch |
|---|---|---|---|
| R1 | 5 字段必填 + 越界 400 | `create_validRequest_returns201` / `create_missingName_returns400` | 2.1 |
| R2 | 10 字段完整定制 + 默认值 | `create_full10Fields` / `create_partial5Fields_defaults` | 2.1 |
| R3 | 12 预设模板 | `listTemplates_returns12` / `createFromTemplate_unknownKey_returns404` | 2.2 |
| R4 | 列表 + 详情 + 跨用户 404 | `list_ordersByLastMessageAt` / `get_otherUsersCompanion_returns404` | 2.1 |
| R5 | PATCH 部分更新 + DELETE 软删 | `update_partialFields` / `softDelete_marksHiddenAndStatus` | 2.1 |
| R6 | 文字流式 LLM | `streamAssistantReply_pushesDeltaAndDone` | 3.3 |
| R7 | WebSocket 鉴权握手 | `handshake_noUserId_rejects` / `handshake_withUserId_allows` | 3.1, 3.4 |
| R8 | 图片识别视觉模型 | `streamAssistantReply_withImageAttachment_usesVisionModel` | 5.2 |
| R9 | 上传落盘 + 越界 413 + 非图 400 | `saveImage_validPng_createsFile` / `saveImage_oversize_rejects` / `saveImage_nonImage_rejects` | 5.1 |
| R10 | 语音消息 ASR→LLM→TTS 完整链路 | `voice.start→frame→stop→text` 端到端；TTS 帧 seq 推 | 6.3 |
| R11 | TTS 单句失败降级不阻断 | `synthesizeSentences_singleFailure_returnsPartial` | 6.2 |
| R12 | 消息历史分页 + 软删 + 重新生成 | `GET /messages?beforeId` / `PATCH message delete|regenerate` | 2.4, 3.3 |
| R13 | 模型下拉列表 | `listModels_returnsAllConfigured` / `normalize_unknownFallsBackToDefault` | 2.3 |
| R14 | 聊天时模型覆盖 + 非法校正 | `streamAssistantReply_modelOverride_applies` | 3.3 |
| R15 | 记忆抽取 0~3 条 + NONE 不写 | `extractAndStore_parsesFacts_writesMySQLAndMilvus` / `extractAndStore_llmReturnsNONE_writesNothing` | 4.2 |
| R16 | 记忆检索 top-K 注入 system prompt | `buildSystemPrompt_withMemories_appendsMemoryBlock` / `buildSystemPrompt_noMemories_omitsBlock` | 4.3 |
| R17 | 记忆管理 + 软删 | `list_memories` / `delete_memory` | 2.4, 4.4 |
| R18 | 跨用户记忆隔离 | `search_filtersByUserIdAndCompanionId` | 4.1 |
| R19 | 亲密度初始化 + 首次 +1 天 | `recordInteraction_firstMessage_setsDaysCountTo1` | 7.1 |
| R20 | 同日累计 + 日上限 +50 | `recordInteraction_sameDay_incrementsByOneUntilCap` | 7.1 |
| R21 | 跨日 +1 天 | `recordInteraction_nextDay_incrementsDaysCount` | 7.1 |
| R22 | 7/30/100 天 milestone 一次性追加 | `recordInteraction_day7Milestone_appendsSystemMessage` / `recordInteraction_milestoneNotReTriggered` | 7.1 |
| R23 | 亲密度只读 API | `GET /intimacy` | 7.3 |
| R24 | system prompt 强制"我是 AI"前缀 | `buildSystemPrompt_firstLineIsCompliance` | 9.1 |
| R25 | AI 标识 banner + 2h 计时 + 敏感词 | 浏览器验收 | 8.7 |
| R26 | 端到端集成 | `CompanionIntegrationTest` | 9.2 |
| R27 | 既有功能零回归 | `mvn -o test` 全绿 + 浏览器人工回归小镇/QA/写作 | 9.2 |

**覆盖结论**：6 个 spec 全部 SHALL/MUST（27 条需求）均已映射到测试义务与批次，无未映射项。

## Execution Batches（依赖顺序，来自 tasks.md）

- **Batch 0** — 迁移基线：5 新表 SQL + `infra/mysql/init.sql` 追加 + Milvus `companion_memories` 初始化脚本 + 12 模板 JSON 种子（交用户手工执行）。
- **Batch 1** — 实体/仓库：5 对 Entity/Repository + 枚举字段 + DTO（CRUD 基础）。
- **Batch 2** — 伴侣 CRUD service + REST API 14 端点 + Model service（接口契约落地）。
- **Batch 3** — LLM 流式聊天：WS 握手 + handler 骨架 + 流式 chat 服务 + WebSocketConfig 注册。
- **Batch 4** — 长期记忆：Milvus 封装 + 抽取/检索/管理 + 注入 system prompt。
- **Batch 5** — 图片识别：上传 service + 视觉模型集成 + REST 上传端点。
- **Batch 6** — 语音消息：ASR service + TTS service + WS 协议整合。
- **Batch 7** — 亲密度 service + 集成到聊天流末尾 + GET 端点。
- **Batch 8** — 前端 #companionView 完整 UI（Tab + 列表 + 聊天 + 图片 + 语音 + 亲密度 + 合规）。
- **Batch 9** — system prompt 合规前缀 + 端到端集成测试。

## Completion Definitions

- **代码批次（B1~B9）**：`mvn -o clean compile` 通过 + 该批次单元/Controller 测试绿 + 契约不变量断言通过（跨用户隔离、模型 normalize、上传越界、TTS 单句失败降级、亲密度日上限、记忆抽取 NONE 不写库）。
- **迁移（B0）**：交付 `migration.sql` + `init-milvus.sh` + `templates.json`；用户确认在 nblm-mysql + nblm-milvus 执行成功（代码侧对缺表/缺 collection 降级不阻断启动）。
- **前端（B8）**：`docker compose up -d --build app` 部署后浏览器逐区块验收（伴侣列表 / 创建向导 / 模板选择 / 文字流式 / 图片识别 / 语音消息录播 / 亲密度显示 / 合规 banner 计时敏感词） + 既有模块（小镇/QA/写作/播客）无回归。
- **集成（B9）**：`CompanionIntegrationTest` 端到端覆盖文字/图片/语音/亲密度/记忆五条链路。

## Review Gates

- 每个已实现的 wave 需 code-reviewer 出具 `ssf execution review` 通过回执后方可进入依赖 wave 或收尾。
- 关闭前：所有已规划 wave 的评审回执均为 pass；delta specs 同步（如有 ADDED 章节）；6 个 spec 均与实现一致。

## Escalation Rules（回退规划的条件）

- proposal 范围扩张超出 Scope Fence（如加入实时语音通话、音色克隆、订阅）→ 回 spec-writer 刷新 specs + contract。
- design 约束变化（如改为 SSE 替代 WebSocket、引入第三方内容审核、模型范围扩到 GPT-5/Claude）→ 刷新 design + 本契约。
- 出现 specs 未覆盖的新需求 → 先补 spec，不在实现里"顺手加"。
- TTS SDK 升级为流式接口 → 重新评估 design D7，但本契约 C6 不强制（保持向后兼容）。
- ASR 切换到非 DashScope 方案 → 重新评估 design D8，本契约 C7 重置。
- **当前无未映射需求、无未决歧义。**
