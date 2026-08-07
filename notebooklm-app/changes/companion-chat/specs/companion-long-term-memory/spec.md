# Spec: companion-long-term-memory

长期记忆：每轮对话后异步抽取"用户偏好/关键事件"，写入 MySQL `companion_memory` + Milvus `companion_memories`；下次聊天按 companion_id 过滤 top-K 检索进 system prompt。

## ADDED Requirements

### Requirement: 记忆抽取（异步）

每轮 assistant 回复落库后，系统 MUST 异步调 `ChatModelFactory.reasoningModel()`（默认 `glm-z1-flash`）抽取本轮对话中"关于用户的偏好/关键事件"为 0~3 条结构化事实（每条 ≤100 字符），prompt 固定为"从以下对话中提取关于用户的事实（用户叫什么、喜欢什么、提到的事件等），每条一行，无则输出 NONE"。抽取结果 MUST 写入 `companion_memory`（含 `companion_id/user_id/content/source_message_id/created_at`），并同步向 Milvus `companion_memories` collection 写入向量（payload 含 companion_id/user_id/content/memory_id），维度 1024，复用 `text-embedding-v3`。

#### Scenario: 单轮抽取到 2 条事实
- WHEN 用户说"我叫小王，喜欢猫"
- THEN 异步任务抽取得到 `["用户叫小王", "用户喜欢猫"]`
- AND `companion_memory` 新增 2 行；Milvus 新增 2 个向量

#### Scenario: 无事实时不写
- WHEN 用户说"今天天气不错"（无用户偏好/事件）
- THEN 抽取结果为 NONE；不写 `companion_memory`，不写 Milvus

#### Scenario: 抽取异步不阻塞聊天
- WHEN 用户发消息
- THEN chat.delta/chat.done 立即推送；记忆抽取在 chat 流式结束后的后台线程执行
- AND 抽取失败（LLM 异常/限流）不抛回聊天流，仅记 warn 日志

### Requirement: 记忆检索（注入 system prompt）

新聊天第一句（`companion_message.role=user` 第一条，或新一轮会话）进入 LLM 前，系统 MUST 按 `companion_id` 过滤在 Milvus `companion_memories` 做 top-5 相似度检索，命中片段拼入 system prompt 末尾：`\n\n[关于用户的记忆]\n- ...\n- ...`。当命中数 = 0 时省略该段。

#### Scenario: 检索命中 3 条
- WHEN 用户开启与伴侣 X 的聊天，Milvus 中 X 共有 10 条记忆
- THEN 相似度 top-5 命中（可能 < 5）拼入 system prompt
- AND 命中片段按相似度降序

#### Scenario: 检索未命中
- WHEN Milvus 中该 companion_id 无记忆
- THEN system prompt 不含 `[关于用户的记忆]` 段

#### Scenario: Milvus 不可用降级
- WHEN Milvus 连接失败
- THEN system prompt 不含记忆段；聊天不报错；记 warn 日志

### Requirement: 记忆管理

GET `/api/companion/{id}/memories` MUST 返回该伴侣全部记忆（按 `created_at DESC`），含 `id/content/createdAt/sourceMessageId`。DELETE `/api/companion/{id}/memories/{memoryId}` SHALL 软删除（`companion_memory.deleted=true`）并从 Milvus 删向量。

#### Scenario: 列出全部记忆
- WHEN 用户 GET
- THEN 返回该伴侣的全部非删记忆

#### Scenario: 软删记忆
- WHEN 用户 DELETE 指定 memoryId
- THEN 204；该记忆后续 GET 不返回；Milvus 删对应向量

### Requirement: 记忆数据隔离

Milvus `companion_memories` 的 payload MUST 始终带 `user_id` 和 `companion_id`；查询时 MUST 按 `user_id` 过滤避免跨用户命中。

#### Scenario: 跨用户记忆不命中
- WHEN 用户 A 在与伴侣 X 聊天时，Milvus 中存有用户 B 的记忆
- THEN 检索结果不含用户 B 的任何记忆
