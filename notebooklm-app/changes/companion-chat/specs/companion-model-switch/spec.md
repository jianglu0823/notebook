# Spec: companion-model-switch

模型选择：每伴侣有 `defaultModel`，聊天时可临时覆盖；模型下拉列表复用 `ChatModelFactory.pricing()`，未知名走 `normalize()` 校正。

## ADDED Requirements

### Requirement: 伴侣默认模型

每个伴侣 MUST 存一个 `defaultModel`（字符串），创建时若未传则落 `ChatModelFactory.defaultTextModel()`。PATCH 伴侣时 `defaultModel` 可更新；MUST 通过 `ChatModelFactory.normalize()` 校验。

#### Scenario: 创建时未指定默认模型
- WHEN POST `/api/companion` 携带 5 字段不指定 `defaultModel`
- THEN `defaultModel=glm-4.7-flash`（或当前 `defaultTextModel()` 返回值）

#### Scenario: PATCH 默认模型
- WHEN 用户 PATCH 携带 `{defaultModel:"qwen-plus"}`
- THEN 200 响应，`defaultModel="qwen-plus"`

#### Scenario: 非法模型名被校正
- WHEN 用户 PATCH 携带 `{defaultModel:"gpt-9999"}`（不在 pricing 列表）
- THEN `defaultModel` 校正为默认（`glm-4.7-flash`），响应回显校正后值

### Requirement: 聊天时模型覆盖

WebSocket chat.text 帧的 `model` 字段 SHALL 优先级高于伴侣 `defaultModel`。未指定或非法值 MUST 走 `defaultModel`，再非法走 `defaultTextModel()`。模型选择不持久化到消息行（消息行的 `metadata.model` 记实际使用值便于回溯）。

#### Scenario: 聊天临时切到 qwen-plus
- WHEN 伴侣 `defaultModel="glm-4.7-flash"`，但 chat.text 携带 `model="qwen-plus"`
- THEN 本次回复用 qwen-plus；`companion_message.metadata.model="qwen-plus"`；伴侣 `defaultModel` 不变

#### Scenario: 聊天不带 model 字段
- WHEN chat.text 不携带 model
- THEN 用伴侣 `defaultModel`；记入 `metadata.model`

### Requirement: 模型下拉列表 API

GET `/api/companion/models` MUST 返回当前 `ChatModelFactory.pricing()` 全量（含 name/label/inputPer1k/outputPer1k）；响应按 `label` 字典序。

#### Scenario: 列出全部可选模型
- WHEN 用户 GET `/api/companion/models`
- THEN 返回数组，每项 `{name, label, inputPer1k, outputPer1k}`；含 GLM 免费 + Qwen 付费全部
