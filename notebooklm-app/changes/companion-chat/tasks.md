# Tasks: AI 伴侣聊天机器人（companion-chat）

包路径根：`notebooklm-app/src/main/java/io/llmnote/companion/`（下称 `companion/`）。
前端：`notebooklm-app/src/main/resources/static/index.html`。
迁移：`infra/mysql/init.sql`（建表基线）+ `changes/companion-chat/migration.sql`（交付用户手工执行的 ALTER）+ `changes/companion-chat/init-milvus.sh`（Milvus collection 初始化脚本，Python 调 pymilvus）。
编译：`cd notebooklm-app && mvn -o clean compile`；测试：`mvn -o test`。

## File Structure

**新建（Create）**

后端：
- `companion/Companion.java` / `CompanionRepository.java` — 伴侣实体 + 仓库。
- `companion/CompanionMessage.java` / `CompanionMessageRepository.java` — 消息实体 + 仓库。
- `companion/CompanionMemory.java` / `CompanionMemoryRepository.java` — 长期记忆元数据 + 仓库。
- `companion/CompanionTemplate.java` / `CompanionTemplateRepository.java` — 预设模板实体 + 仓库。
- `companion/CompanionIntimacy.java` / `CompanionIntimacyRepository.java` — 亲密度实体 + 仓库。
- `companion/CompanionService.java` — 伴侣 CRUD + 模板创建。
- `companion/CompanionController.java` — REST API。
- `companion/CompanionChatService.java` — LLM 流式调用 + system prompt 构建 + 消息落库。
- `companion/CompanionMemoryService.java` — 记忆抽取 + 写库 + 检索。
- `companion/CompanionIntimacyService.java` — 亲密度计数 + milestone 触发。
- `companion/CompanionUploadService.java` — 图片上传落盘。
- `companion/CompanionAsrService.java` — 包装 DashScope `paraformer-realtime-v2` Recognition。
- `companion/CompanionTtsService.java` — 包装 DashScope `cosyvoice-v1` SpeechSynthesizer（按句切分伪流式）。
- `companion/CompanionModelService.java` — 暴露 `ChatModelFactory.pricing()` + `normalize()` 包装。
- `companion/ws/CompanionWsHandler.java` — WebSocket handler（JSON + binary 混合协议）。
- `companion/ws/CompanionHandshakeInterceptor.java` — WebSocket 握手鉴权（复制 `VoiceHandshakeInterceptor` 模式）。
- `companion/ws/CompanionSessionRegistry.java` — 维护 `userId → session` 映射，便于广播/清理。
- `companion/dto/*.java` — CompanionDto / MessageDto / MemoryDto / IntimacyDto / ModelDto / UploadResponse 等 DTO。
- `companion/config/CompanionWebSocketConfig.java` — 注册 `/ws/companion` 端点。
- `companion/milvus/CompanionMemoryVectorStore.java` — Milvus collection 封装（`companion_memories`，dim=1024，IP 距离）。

资源：
- `companion-init/templates.json` — 12 预设模板 JSON 种子（init 时 load）。
- `changes/companion-chat/migration.sql` — 5 张新表 CREATE/ALTER 脚本。
- `changes/companion-chat/init-milvus.sh` — Python 脚本调 pymilvus 创建 collection（如已有则跳过）。

测试：
- `test/.../companion/CompanionServiceTest.java` — CRUD + 跨用户隔离。
- `test/.../companion/CompanionChatServiceTest.java` — 流式 LLM + system prompt 注入。
- `test/.../companion/CompanionMemoryServiceTest.java` — 抽取 + 写库 + 检索。
- `test/.../companion/CompanionIntimacyServiceTest.java` — 计数 + milestone。
- `test/.../companion/CompanionUploadServiceTest.java` — 上传落盘 + 越界拒绝。
- `test/.../companion/CompanionControllerTest.java` — REST MockMvc。

**修改（Modify）**
- `infra/mysql/init.sql` — 追加 5 张新表 CREATE TABLE（建表基线）。
- `src/main/java/io/llmnote/voice/WebSocketConfig.java` — 注册新路径 `/ws/companion`（既有 `/ws/voice` 不动）。
- `src/main/java/io/llmnote/auth/AuthFilter.java` — 放行 `/api/companion/**`。
- `src/main/resources/application.yml` — `notebooklm.companion.*` 配置项（默认模型、记忆检索 K、TTS 音色映射、ASR 模型）。
- `static/index.html` — 新增 #companionView（Tab + 列表 + 聊天 + 配置抽屉 + WebSocket 客户端 + 录音 + 播音 + 计时 + 敏感词）。

## Interfaces

跨批次契约（exact types）：

```java
// 伴侣 CRUD（Batch 2 产出）
Long CompanionService.create(Long userId, CompanionCreateRequest req);
CompanionDto CompanionService.get(Long userId, Long companionId);
List<CompanionDto> CompanionService.list(Long userId);
CompanionDto CompanionService.update(Long userId, Long companionId, CompanionPatchRequest req);
void CompanionService.softDelete(Long userId, Long companionId);
List<CompanionTemplateDto> CompanionService.listTemplates();
Long CompanionService.createFromTemplate(Long userId, String templateKey);

// 模型下拉（Batch 2 产出）
List<ModelDto> CompanionModelService.listModels();
String CompanionModelService.normalize(String name); // 透传 ChatModelFactory.normalize

// 消息发送流式（Batch 3 产出，WebSocket 消费）
void CompanionChatService.handleTextMessage(Long userId, Long companionId, String text,
                                            String modelOverride, String requestId,
                                            WebSocketSession session);

// 消息流式推送（Batch 3 产出）
void CompanionChatService.streamAssistantReply(Long userId, Long companionId,
                                               String userText, String model, String requestId,
                                               List<AttachmentDto> attachments,
                                               WebSocketSession session);
// 内部流程：buildSystemPrompt → ChatModelFactory.streamTextWithFallback →
//  逐 chunk 推 session.sendMessage(chat.delta) → 完整文本落 companion_message →
//  推 chat.done → CompletableFuture 异步调 CompanionMemoryService.extract

// system prompt 构建（Batch 3 产出，被 streamAssistantReply 调用）
String CompanionChatService.buildSystemPrompt(Long userId, Long companionId, Companion c);
// 顺序：合规前缀("你是一个 AI 角色...") → 伴侣 backgroundStory + personalityTags + relationshipType
//   → 长期记忆 top-5 片段（若无则省略）→ 行为约束（"回复 ≤200 字，除非用户要求详细"）

// 记忆抽取（Batch 4 产出，async）
CompletableFuture<Void> CompanionMemoryService.extractAndStore(Long userId, Long companionId, Long messageId, String userText, String assistantText);
List<String> CompanionMemoryService.searchTopK(Long userId, Long companionId, String query, int k); // k=5

// 记忆管理（Batch 4 产出，REST API 消费）
List<MemoryDto> CompanionMemoryService.list(Long userId, Long companionId);
void CompanionMemoryService.softDelete(Long userId, Long companionId, Long memoryId);

// 亲密度（Batch 7 产出，REST + chat 流式末尾消费）
IntimacyDto CompanionIntimacyService.get(Long userId, Long companionId);
void CompanionIntimacyService.recordInteraction(Long userId, Long companionId);
// 内部：score += min(1, 50 - todayCount); todayCount = SELECT COUNT(*) FROM companion_message
//  WHERE companion_id=? AND role='user' AND DATE(created_at)=today; 跨日 daysCount+1; 检查 milestone

// 上传（Batch 5 产出，REST 消费）
UploadResponse CompanionUploadService.saveImage(MultipartFile file);
// 落 uploads/companion/<yyyy-MM-dd>/<uuid>.<ext>; 越界 413; 非 image/* 400

// ASR session（Batch 6 产出，WS 消费）
Recognition CompanionAsrService.start(WebSocketSession session, String requestId, ResultCallback<RecognitionResult> cb);
void CompanionAsrService.sendFrame(WebSocketSession session, ByteBuffer pcm);
void CompanionAsrService.stop(WebSocketSession session);

// TTS 按句（Batch 6 产出，chat 流式末尾消费）
List<byte[]> CompanionTtsService.synthesizeSentences(String fullText, String voice);
// 按 [。！？!?\n] 切分；每句调 SpeechSynthesizer.call → MP3 字节列表

// WebSocket 帧协议（所有 batch 共用常量）
String TYPE_CHAT_TEXT = "chat.text";
String TYPE_CHAT_DELTA = "chat.delta";
String TYPE_CHAT_DONE = "chat.done";
String TYPE_CHAT_AUDIO = "chat.audio";
String TYPE_CHAT_AUDIO_DONE = "chat.audio.done";
String TYPE_VOICE_START = "voice.start";
String TYPE_VOICE_READY = "voice.ready";
String TYPE_VOICE_STOP = "voice.stop";
String TYPE_VOICE_TEXT = "voice.text";
String TYPE_VOICE_ERROR = "voice.error";
String TYPE_ACK = "ack";
String TYPE_ERROR = "error";
```

配置常量（`application.yml` → `notebooklm.companion.*`）：

```yaml
notebooklm:
  companion:
    default-model: glm-4.7-flash  # 兜底,与 ChatModelFactory.defaultTextModel 一致
    memory-search-k: 5
    tts:
      model: cosyvoice-v1
      voice-map: # voice_style → cosyvoice voice name
        温柔甜美: longwan
        低沉磁性: longcheng
        清亮少年: longwan
        成熟御姐: longcheng
        奶萌可爱: longwan
        沙哑质感: longcheng
        知性平静: longwan
    asr:
      model: paraformer-realtime-v2
      sample-rate: 16000
      format: pcm
    intimacy:
      per-message-score: 1
      daily-cap: 50
    vision:
      glm-model: glm-4.6v-flash
      qwen-model: qwen-vl-max
```

---

## Batch 0：数据库迁移基线（无代码依赖）

### Task 0.1 — 编写迁移 SQL 与 init.sql 基线
- **文件**：`changes/companion-chat/migration.sql`（新建）、`infra/mysql/init.sql`（修改）。
- **步骤**：
  1. 在 `init.sql` 末尾追加 5 张新表的 `CREATE TABLE`：`companion`（含 user_id/status/intimacy_score 等）、`companion_message`（含 companion_id/role/hidden/created_at）、`companion_memory`（含 companion_id/source_message_id/deleted）、`companion_template`（template_key PK）、`companion_intimacy`（companion_id PK）。
  2. 追加 `companion_message` 索引 `(companion_id, id)` 和 `(companion_id, created_at)`。
  3. `migration.sql` = 上述 CREATE 的 `CREATE TABLE IF NOT EXISTS` 版本 + 索引注释说明"若已存在则跳过"。
  4. **不在本步执行**（交用户手工跑）。
- **验证**：SQL 语法自查（MySQL 8）。
- **Depends on**：无。

### Task 0.2 — Milvus collection 初始化脚本
- **文件**：`changes/companion-chat/init-milvus.sh`（新建）+ `changes/companion-chat/init-milvus.py`（新建，pymilvus）。
- **步骤**：
  1. Python 脚本连 Milvus（host/port 沿用现有 `nblm_chunks` 配置），检查 `companion_memories` collection 是否存在；不存在则创建（dim=1024, metric_type=IP, fields: id INT64 PK auto, embedding FLOAT_VECTOR(1024), companion_id INT64, user_id INT64, memory_id INT64, content VARCHAR(512)）。
  2. shell 脚本封装 docker exec 进 nblm-milvus 容器调 python（如 pymilvus 镜像里没装则用 `pip install pymilvus && python init-milvus.py`）。
  3. **不在本步执行**（交用户手工跑）。
- **验证**：脚本能 dry-run 检测 collection 状态。
- **Depends on**：无。

### Task 0.3 — 12 预设模板种子
- **文件**：`companion-init/templates.json`（新建）、`companion/CompanionTemplateSeeder.java`（新建，`CommandLineRunner` 在 init 时 load）。
- **步骤**：
  1. JSON 写 12 个模板的完整 10 字段 + `template_key`（如 `gentle_senior` / `domestic_ceo` / `energetic_girl` / `cold_swordsman` / `toxic_buddy` / `elegant_lady` / `versatile_butler` / `cat_maid` / `academic_mentor` / `rock_singer` / `mage` / `workplace_senior`）。
  2. `CompanionTemplateSeeder` 启动时 SELECT count(*) FROM companion_template；若 = 12 则跳过；否则 `saveAll(...)` 写入（upsert 按 `template_key`）。
  3. 不依赖 DB schema（仅要求 `companion_template` 表存在），故排在 Batch 1 实体定义完成后才生效。
- **验证**：单元测试 mock repository 验证"首次启动写入 12 条、再次启动跳过"。
- **Depends on**：Task 1.1（实体）、Task 0.1（表存在）。

---

## Batch 1：实体 + 仓库（依赖 Batch 0 表结构）

### Task 1.1 — 5 个实体与仓库
- **文件**：`companion/` 下新建 5 对 Entity/Repository。
- **TDD**：
  1. **Red**：先写 `CompanionRepositoryTest.existsByUserIdAndStatus` 空库返回 false。
  2. **Green**：按 design 表结构写 `@Entity`/`extends JpaRepository`；字段 `@Column(name=...)` 对齐 init.sql 列名。
  3. 补 Interface 段声明的查询方法：`findByUserIdAndStatusOrderByLastMessageAtDescIdDesc`、`findByUserIdAndId`（get 用）、`findByCompanionIdAndIdLessThanOrderByIdDesc`（分页）、`findByCompanionIdAndHiddenFalseOrderByIdDesc`（消息列表）、`countByCompanionIdAndRoleAndCreatedAtBetween`（今日用户消息数，亲密度用）、`findByCompanionIdAndDeletedFalseOrderByCreatedAtDesc`（记忆列表）、`existsByTemplateKey`（seeder 用）。
  4. **Refactor**：统一 `@Data`+`@Entity` 风格，与 `AgentTransaction` 一致。
  5. `mvn -o clean compile` 通过。
- **Depends on**：Batch 0（表已创建；测试用 H2 或 Testcontainers 模拟）。

### Task 1.2 — Companion 实体枚举字段
- **文件**：`companion/Companion.java`（修改）。
- **步骤**：
  1. 把 `gender/personalityTags/voiceStyle/relationshipType/speakingStyle` 字段映射为 Java 枚举（companion.enums 包下）：`Gender` / `PersonalityTag`(15 个) / `VoiceStyle`(7 个) / `RelationshipType`(6 个) / `SpeakingStyle`(7 个)。
  2. `@Enumerated(EnumType.STRING)` 持久化为字符串（可读、可演进）。
  3. `personalityTags` 用 `@ElementCollection` + 独立表 OR 用 JSON 字段（`@Convert` + `AttributeConverter<List<PersonalityTag>>`）；建议 JSON 字段（`JSON_ARRAY` 列）减少表数量。
- **验证**：单元测试枚举与字符串互转。
- **Depends on**：Task 1.1。

### Task 1.3 — DTO 与 MapStruct/手写转换器
- **文件**：`companion/dto/*`（新建）。
- **步骤**：
  1. DTO 用 record：`CompanionDto` / `CompanionCreateRequest` / `CompanionPatchRequest` / `MessageDto` / `MemoryDto` / `IntimacyDto` / `ModelDto` / `UploadResponse` / `AttachmentDto`。
  2. 手写 `CompanionMapper.toDto(Companion)` 等转换方法（项目未见 MapStruct 依赖）。
- **Depends on**：Task 1.1, 1.2。

---

## Batch 2：伴侣 CRUD service + REST API（依赖 Batch 1）

### Task 2.1 — CompanionService 核心 CRUD
- **文件**：`companion/CompanionService.java`（新建）。
- **TDD**：
  1. **Red**：`create_validRequest_returns201`、`create_missingName_returns400`、`get_otherUsersCompanion_returns404`、`list_ordersByLastMessageAt`、`update_partialFields`、`softDelete_marksHiddenAndStatus`。
  2. **Green**：实现 7 个 public 方法，严格按 `userId` 过滤；删除时同步软删 `companion_message`（`hidden=true`）和 `companion_memory`（`deleted=true`）以及 Milvus 向量。
  3. **Refactor**：抽 `requireOwnedCompanion(userId, id)` 工具方法避免重复。
  4. 测试通过。
- **Depends on**：Task 1.1, 1.3。

### Task 2.2 — 模板服务 + 从模板创建
- **文件**：`companion/CompanionService.java`（修改）。
- **TDD**：`listTemplates_returns12`、`createFromTemplate_unknownKey_returns404`、`createFromTemplate_copiesAllFields`。
- **Green**：补 listTemplates / createFromTemplate。
- **Depends on**：Task 1.3（CompanionTemplateRepository 已有）、Task 2.1。

### Task 2.3 — CompanionModelService
- **文件**：`companion/CompanionModelService.java`（新建）。
- **步骤**：
  1. 注入 `ChatModelFactory`，`listModels()` 直接返回 `factory.pricing()` 转 DTO（按 label 排序）。
  2. `normalize(name)` 透传 `factory.normalize(name)`。
- **测试**：`listModels_returnsAllConfigured`、`normalize_unknownFallsBackToDefault`。
- **Depends on**：Batch 1 仓库（间接），无强依赖。

### Task 2.4 — CompanionController REST API
- **文件**：`companion/CompanionController.java`（新建）。
- **TDD（MockMvc）**：
  1. `GET /api/companion` 返回 200 + 数组。
  2. `GET /api/companion/{id}` 200 / 404。
  3. `POST /api/companion` 201 / 400。
  4. `PATCH /api/companion/{id}` 200。
  5. `DELETE /api/companion/{id}` 204。
  6. `GET /api/companion/templates` 200 + 12 项。
  7. `POST /api/companion/from-template/{key}` 201 / 404。
  8. `GET /api/companion/models` 200 + 数组。
  9. `GET /api/companion/{id}/intimacy` 200（占位,Batch 7 完善）。
  10. `GET /api/companion/{id}/messages?beforeId=N&limit=50` 200。
  11. `PATCH /api/companion/message/{id}?action=delete|regenerate` 204/200。
  12. `GET /api/companion/{id}/memories` 200。
  13. `DELETE /api/companion/{id}/memories/{memoryId}` 204。
  14. `POST /api/companion/chat/upload` 201 / 413 / 400。
- **Green**：实现全部 14 端点；上传 `consumes=multipart/form-data`，其余 `application/json`。
- **Refactor**：用 `@RestControllerAdvice` 统一 400/404/413 错误体。
- **Depends on**：Task 2.1, 2.2, 2.3 + Batch 1 全部。

---

## Batch 3：LLM 流式聊天 WebSocket（依赖 Batch 2）

### Task 3.1 — CompanionHandshakeInterceptor
- **文件**：`companion/ws/CompanionHandshakeInterceptor.java`（新建）。
- **步骤**：复制 `io.llmnote.voice.VoiceHandshakeInterceptor` 的握手逻辑（读 cookie/JWT、设 attributes `userId`），保持实现一致；不修改原 voice 包的代码。
- **验证**：单元测试无 userId 时拒绝、有 userId 时放行。
- **Depends on**：无。

### Task 3.2 — CompanionWsHandler 基础骨架
- **文件**：`companion/ws/CompanionWsHandler.java`（新建）。
- **步骤**：
  1. 继承 `TextWebSocketHandler`（不是 `BinaryWebSocketHandler`）。
  2. 覆盖 `afterConnectionEstablished`（记录 session）、`afterConnectionClosed`（清理 session + 停 ASR）。
  3. 覆盖 `handleTextMessage` 解析 JSON `type` 字段分发到 `onChatText/onVoiceStart/onVoiceStop/onChatRegenerate`。
  4. 覆盖 `handleBinaryMessage` 仅用于 voice PCM 推流。
  5. 工具方法 `sendJson(session, payload)` / `sendBinary(session, bytes)`。
- **验证**：单元测试分发逻辑（mock ObjectMapper 解析）。
- **Depends on**：Task 3.1。

### Task 3.3 — CompanionChatService 流式 LLM
- **文件**：`companion/CompanionChatService.java`（新建）。
- **TDD**：
  1. **Red**：`buildSystemPrompt_includesCompliancePrefix`（首句是"你是一个 AI 角色..."）、`buildSystemPrompt_includesMemories`（命中 3 条记忆拼入）、`streamAssistantReply_pushesDeltaAndDone`（mock ChatModelBase 流式响应，断言 WebSocket 收到 N 个 chat.delta + 1 个 chat.done）。
  2. **Green**：实现 `buildSystemPrompt`（固定顺序）、`streamAssistantReply`（构造 `List<Msg>` → `factory.streamTextWithFallback` → 逐 chunk 推 session → 完整文本落库 → 推 done → async 触发记忆抽取）。
  3. **Refactor**：抽 `appendUserMessage(companionId, content, attachments)` / `appendAssistantMessage(companionId, content, model)` 工具。
  4. 测试通过。
- **Depends on**：Batch 2（CompanionService for 验证 ownership）、Task 3.2（session 接口）。

### Task 3.4 — WebSocketConfig 注册 `/ws/companion`
- **文件**：`companion/config/CompanionWebSocketConfig.java`（新建） + `src/main/java/io/llmnote/voice/WebSocketConfig.java`（修改，追加 companion 端点注册）。
- **步骤**：在 `WebSocketConfig` 已有 `/ws/voice` 注册旁追加 `registry.addHandler(new CompanionWsHandler(...), "/ws/companion").addInterceptors(new CompanionHandshakeInterceptor()).setAllowedOriginPatterns("*")`。
- **验证**：`mvn -o clean compile`；本地 `wscat -c ws://localhost:8080/ws/companion` 验证握手。
- **Depends on**：Task 3.1, 3.2。

---

## Batch 4：长期记忆（依赖 Batch 3）

### Task 4.1 — Milvus 封装
- **文件**：`companion/milvus/CompanionMemoryVectorStore.java`（新建）。
- **TDD**：
  1. `ensureCollection_createsIfNotExists`（mock Milvus client，断言 createCollection 被调）。
  2. `insert_writesVectorWithPayload`（断言 payload 包含 companion_id/user_id/memory_id/content）。
  3. `search_filtersByUserIdAndCompanionId`（断言 search 时 filter 表达式含 `user_id==X && companion_id==Y`）。
  4. `deleteByMemoryId_removesVector`。
- **Green**：用 pymilvus Java SDK（应已在依赖内）或 Milvus REST。
- **Depends on**：Batch 0（collection 已建）、Batch 1（repository）。

### Task 4.2 — CompanionMemoryService 抽取 + 检索
- **文件**：`companion/CompanionMemoryService.java`（新建）。
- **TDD**：
  1. `extractAndStore_parsesFacts_writesMySQLAndMilvus`：mock LLM 返回 `["用户叫小王","用户喜欢猫"]`，断言写 2 行 `companion_memory` + 2 个 Milvus 向量。
  2. `extractAndStore_llmReturnsNONE_writesNothing`：mock LLM 返回 `"NONE"`，不写库。
  3. `extractAndStore_llmFails_doesNotThrow`：mock LLM 抛异常，方法返回但不抛。
  4. `searchTopK_returnsMemoriesSortedByScore`：mock Milvus search 返回 3 条结果，按 score 降序。
  5. `searchTopK_milvusUnavailable_returnsEmpty`：mock Milvus 异常，方法返回 `List.of()` 不抛。
- **Green**：实现抽取 prompt 固定中文（"从以下对话中提取关于用户的事实..."）、解析按行 split + 过滤空行 + 限 ≤100 字符 + 限 3 条；写 MySQL 后写 Milvus；失败回滚。
- **Depends on**：Task 4.1, Task 3.3（嵌入点）。

### Task 4.3 — buildSystemPrompt 注入记忆
- **文件**：`companion/CompanionChatService.java`（修改，扩展 buildSystemPrompt）。
- **TDD**：
  1. `buildSystemPrompt_withMemories_appendsMemoryBlock`：mock memory service 返回 3 条，断言 prompt 末尾有 `[关于用户的记忆]` 段。
  2. `buildSystemPrompt_noMemories_omitsBlock`：mock 返回 `List.of()`，断言无该段。
- **Depends on**：Task 4.2, Task 3.3。

### Task 4.4 — 记忆管理端点
- **文件**：`companion/CompanionMemoryService.java`（修改）+ `companion/CompanionController.java`（修改）。
- **TDD**：`list_memories` / `delete_memory` 已在 Batch 2 Task 2.4 列出，此处填实现：`list` 直接调 repo，`delete` 软删 + Milvus 删。
- **Depends on**：Task 4.1, Task 2.4。

---

## Batch 5：图片识别（依赖 Batch 3）

### Task 5.1 — CompanionUploadService
- **文件**：`companion/CompanionUploadService.java`（新建）。
- **TDD**：
  1. `saveImage_validPng_createsFile`：mock MultipartFile，断言落盘到 `uploads/companion/<date>/<uuid>.png`。
  2. `saveImage_oversize_rejects`：传 11MB 抛 `MaxUploadSizeExceededException`。
  3. `saveImage_nonImage_rejects`：content-type 非 image/* 抛 `IllegalArgumentException`。
- **Green**：实现 `Path uploadDir = Paths.get(props.getStorage().getUploadDir() + "/companion/" + LocalDate.now())`，UUID 文件名；`Files.createDirectories` + `Files.copy`。
- **Depends on**：Batch 1。

### Task 5.2 — 视觉模型集成
- **文件**：`companion/CompanionChatService.java`（修改，streamAssistantReply 接受 `List<AttachmentDto>`）。
- **TDD**：
  1. `streamAssistantReply_withImageAttachment_usesVisionModel`：mock ChatModelBase 收到含图片 URL 的 Msg，断言返回流式文本。
  2. `streamAssistantReply_withImageAndText_bothInContext`。
- **Green**：用 AgentScope `Msg.builder().content(List.of(TextBlock, ImageBlock)).build()`（参考 XhsService 视觉调用模式）；按 design D10 选模型。
- **Depends on**：Task 3.3, Task 5.1。

### Task 5.3 — 上传 REST 端点
- **文件**：`companion/CompanionController.java`（修改）。
- **TDD**：`POST /api/companion/chat/upload` MockMvc 200/413/400，引用 Task 2.4 的 #14 端点。
- **Depends on**：Task 5.1。

---

## Batch 6：语音消息 ASR + TTS（依赖 Batch 3）

### Task 6.1 — CompanionAsrService
- **文件**：`companion/CompanionAsrService.java`（新建）。
- **TDD**：
  1. `start_createsRecognition`：mock Recognition，断言 `call(RecognitionParam, ResultCallback)` 被调。
  2. `sendFrame_writesToRecognition`：mock buf 推入后断言 `sendAudioFrame` 被调。
  3. `stop_terminatesRecognition`。
- **Green**：复制 `VoiceWsHandler` 的 Recognition 包装代码（参数从配置读）。
- **Depends on**：无（与 voice 包独立）。

### Task 6.2 — CompanionTtsService
- **文件**：`companion/CompanionTtsService.java`（新建）。
- **TDD**：
  1. `synthesizeSentences_splitsByPunctuation`：输入 `"你好。今天天气不错！"` 输出 ≥3 句的 MP3 字节列表。
  2. `synthesizeSentences_voiceMap_fallsBackToDefault`：voice_style 不在 map 时用 `longwan`。
  3. `synthesizeSentences_singleFailure_returnsPartial`：mock SpeechSynthesizer 第 2 句抛异常，结果是 3 句中的前 1 句 + 第 3 句（中间跳过），不抛。
- **Green**：用 `SpeechSynthesisParam.builder().text(s).voice(voiceMap.getOrDefault(style, "longwan")).format(MP3_22050HZ_MONO_256KBPS).build()`；`SpeechSynthesizer.call(param)` 同步返回 MP3 bytes；try/catch 隔离单句失败。
- **Depends on**：无。

### Task 6.3 — 语音消息 WebSocket 协议
- **文件**：`companion/ws/CompanionWsHandler.java`（修改）+ `companion/CompanionChatService.java`（修改，streamAssistantReply 末尾增加 TTS 流）。
- **TDD**：
  1. **WS 协议**：`onVoiceStart` 起 ASR session，回 `voice.ready`；`handleBinaryMessage` 推 PCM；`onVoiceStop` 停 ASR，回 `voice.text`；客户端再发 `chat.text` 走 Batch 3 流程。
  2. **TTS 推流**：streamAssistantReply 末尾调 `ttsService.synthesizeSentences(fullText, voice)` → 逐句推 binary 帧（每帧前 4 字节 seq + MP3 bytes） + 文本头 `chat.audio`；全部完成推 `chat.audio.done`。
  3. **TTS 失败不阻断**：单句失败记 warn，跳过该帧。
- **Green**：实现 WS 协议 + TTS 推流。
- **Depends on**：Task 6.1, 6.2, Task 3.3。

---

## Batch 7：亲密度（依赖 Batch 2）

### Task 7.1 — CompanionIntimacyService
- **文件**：`companion/CompanionIntimacyService.java`（新建）。
- **TDD**：
  1. `get_returnsCurrentState`：mock 已有行，返回 DTO。
  2. `recordInteraction_firstMessage_setsDaysCountTo1`。
  3. `recordInteraction_sameDay_incrementsByOneUntilCap`：mock 今日 60 条，score 累计 +50 封顶。
  4. `recordInteraction_nextDay_incrementsDaysCount`：mock last_interact_date=昨天。
  5. `recordInteraction_day7Milestone_appendsSystemMessage`：mock days_count=6 → 7，断言 companion_message 新增 system 行 + milestone_7_sent=true。
  6. `recordInteraction_milestoneNotReTriggered`：mock milestone_7_sent 已 true，days_count 变 7 不重复追加。
- **Green**：实现 recordInteraction 顺序：查 row（无则 init from `intimacyStart`）→ 今日用户消息数聚合 → score += min(1, cap - todayCount) → 跨日 daysCount+1 → 写 row → 检查 milestone。
- **Depends on**：Task 1.1, 2.1。

### Task 7.2 — 集成到聊天流末尾
- **文件**：`companion/CompanionChatService.java`（修改，streamAssistantReply 落 assistant 消息后调 `intimacyService.recordInteraction`）。
- **TDD**：`streamAssistantReply_completionCallsRecordInteraction`：mock，断言 stream 完成后 recordInteraction 被调一次。
- **Depends on**：Task 7.1, Task 3.3。

### Task 7.3 — 亲密度 GET 端点
- **文件**：`companion/CompanionController.java`（修改，引用 Batch 2 Task 2.4 的 #9 端点）。
- **Depends on**：Task 7.1。

---

## Batch 8：前端 #companionView（依赖 Batch 1~7 的 API 与 WS 协议全部就绪）

### Task 8.1 — 顶部 Tab + 视图骨架
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. 顶部 tab 列表追加"伴侣"按钮（既有 #worldView 等 tab 旁边）。
  2. 新增 `<section id="companionView" hidden>`，内含 3 列：左 `<aside id="companionList">` / 中 `<main id="companionChat">` / 右 `<aside id="companionConfig" hidden>`（配置抽屉）。
  3. 切换函数 `showView('companion')` 沿用既有模式。
  4. JS 命名空间 `window.CompanionView = { init, showList, openChat, openConfig, ... }`。
- **验证**：浏览器目视 + 既有 tab 无回归。
- **Depends on**：Batch 1~7 全部（API 存在即可联调）。

### Task 8.2 — 伴侣列表 + 创建/编辑表单
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. 启动调 `GET /api/companion` 渲染左侧列表（avatar emoji + name + intimacy）。
  2. "+ 新建"按钮弹模态框：5 字段必填 + 高级模式展开 5 字段；可点"从模板创建"展开 12 模板网格。
  3. 点伴侣项 → 调 `GET /api/companion/{id}` 填充聊天区与配置抽屉。
  4. 配置抽屉可编辑 10 字段、修改默认模型下拉（`GET /api/companion/models`）、保存 PATCH。
  5. 软删按钮二次确认。
- **Depends on**：Batch 2 API。

### Task 8.3 — WebSocket 客户端 + 文字流式聊天
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. 进入伴侣视图时 `new WebSocket('ws://' + host + '/ws/companion')`，自动用既有 cookie 鉴权。
  2. 聊天区顶部固定 AI 标识 banner "AI 角色，生成内容仅供参考"。
  3. 输入框 + 发送按钮：发 `chat.text` 帧；收到 `chat.delta` 追加到气泡；`chat.done` 完成态。
  4. 消息操作：hover 显示"删除/重新生成"按钮，调 REST。
  5. 历史消息 `GET /api/companion/{id}/messages` 渲染在聊天区。
- **Depends on**：Batch 3 WS。

### Task 8.4 — 图片识别 UI
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. 输入框旁"图片"按钮 → `<input type=file accept="image/*" hidden>` → 选完后 fetch POST `/api/companion/chat/upload` → 拿到 url → 构造 attachments → 走 chat.text。
  2. 消息气泡渲染图片（缩略图）。
- **Depends on**：Batch 5。

### Task 8.5 — 语音消息 UI（录音 + 播音）
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. "麦克风"按钮 → `navigator.mediaDevices.getUserMedia({audio:true})` + `AudioContext(16kHz)` + `AudioWorkletNode` 或 `ScriptProcessorNode` 把麦克风 PCM 重采样到 16kHz mono Int16 → `ws.send(int16)`。
  2. 录完点"完成" → ws 发 `voice.stop` → 收 `voice.text` 自动回填输入框 → 自动发 `chat.text`。
  3. 收 `chat.audio` binary 帧：解析前 4 字节 seq、之后 MP3 bytes → `audioContext.decodeAudioData` → `AudioBufferSourceNode` 排队播放（用 `start(when, offset, duration)` 串行）。
  4. 收 `chat.audio.done` 标记播放结束。
  5. UI 显示"播放中..."指示器。
- **Depends on**：Batch 6。

### Task 8.6 — 亲密度显示
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. 聊天区顶部右侧显示"亲密度: 85 | 已认识 12 天"（`GET /api/companion/{id}/intimacy`）。
  2. 进入聊天时拉一次；流式 chat.done 后再拉一次刷新。
- **Depends on**：Batch 7。

### Task 8.7 — 合规最小实现
- **文件**：`static/index.html`（修改）。
- **步骤**：
  1. AI 标识 banner 已在 8.3 写。
  2. 2h 计时：`setInterval(60000)` 累加 1，达到 120 弹窗 `alert("你已经连续使用伴侣聊天 2 小时，建议休息一下。")` 后归零。
  3. 离开伴侣视图调 `clearInterval`，切回重启。
  4. 敏感词正则：消息发送后正则 `/(自杀|自残|不想活|轻生|想死)/.test(content)` 匹配时在气泡下加小字提示。
- **Depends on**：无（独立前端逻辑）。

---

## Batch 9：合规 + 集成验证（依赖全部后端）

### Task 9.1 — system prompt 合规前缀
- **文件**：`companion/CompanionChatService.java`（修改，buildSystemPrompt 最前固定拼）。
- **TDD**：`buildSystemPrompt_firstLineIsCompliance`：断言首行是固定中文。
- **Depends on**：Task 3.3（已含 buildSystemPrompt 雏形）。

### Task 9.2 — 端到端集成测试
- **文件**：`test/.../companion/CompanionIntegrationTest.java`（新建）。
- **TDD**（Testcontainers 或 mock 全栈）：
  1. 创建伴侣 → WebSocket 连 → 发文字 → 收流式回复 → 检查消息落库。
  2. 上传图片 → 发文字带图片 → 收视觉回复。
  3. 模拟 voice 帧 → 收 voice.text → 触发 chat 流 → 收 TTS 帧。
  4. 检查亲密度 +1。
  5. 检查 companion_memory 新增记录 + Milvus 新增向量。
- **Depends on**：Batch 1~8 全部。

---

## 验证与交付顺序
1. Batch 0 交付 `migration.sql` + `init-milvus.sh`，提示用户手工在 nblm-mysql 与 nblm-milvus 执行（我不直连）。
2. Batch 1~8 每批 `mvn -o clean compile` / `mvn -o test` 绿。
3. Batch 8 后 `set -a && . ./.env && set +a && docker compose up -d --build app` 部署，浏览器逐项验收。
4. 观察 `docker logs nblm-app`：WS 握手成功、LLM 流式无中断、ASR/TTS 无异常、Milvus 检索无报错。
5. 单元测试守住不变量：跨用户隔离、模型名 normalize、上传越界、TTS 单句失败降级、亲密度日上限、记忆抽取 NONE 不写库。

## 需求→任务映射

| Spec 能力 | 涉及任务 |
|---|---|
| companion-crud | 0.1（init.sql）、1.1~1.3（实体 DTO）、2.1~2.4（service + controller） |
| companion-multimodal-chat | 3.1~3.4（WS 骨架 + 文字流式）、5.1~5.3（图片）、6.1~6.3（语音）、8.3~8.5（前端 WS + 图片 + 录音播音） |
| companion-model-switch | 1.1（companion.default_model）、2.3（model service）、2.4（GET /models）、3.3（normalize）、8.2（下拉 UI） |
| companion-long-term-memory | 0.2（Milvus init）、1.1（companion_memory 实体）、4.1~4.4（service + 检索 + 管理）、8.3（前端列表可后续） |
| companion-intimacy | 1.1（companion_intimacy 实体）、7.1~7.3（service + 集成 + API）、8.6（前端显示） |
| companion-compliance-ui | 9.1（system prompt 前缀）、8.7（前端 banner + 计时 + 敏感词） |
