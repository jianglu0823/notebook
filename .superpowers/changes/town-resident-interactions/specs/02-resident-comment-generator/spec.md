# 02: 居民评论生成

## ADDED Requirements

### Requirement: ResidentCommentGenerator MUST generate a 1-3 sentence Chinese short comment
`ResidentCommentGenerator.generate(persona, authorName, title, content, catLabel)` MUST return a String of 1-200 Chinese characters. The generator MUST call `modelFactory.streamTextWithFallback('qwen-turbo', msgs)` and take the last `text` segment. The prompt MUST strictly constrain "1-3 sentences / not over 80 chars / no prefix / no explanation".

#### Scenario: 正常返回
- WHEN LLM 返回 "哈哈哈下雪真好玩,我上次也踩了一脚"
- THEN 输出原文(去首尾空白)

#### Scenario: 包含 think 块
- WHEN LLM 返回 `<think>用户想让我写评论...</think>下雪真好玩`
- THEN 去掉 think 块,只返回 "下雪真好玩"

#### Scenario: 包含元话语
- WHEN LLM 返回 "作为 AI 助手,我觉得这个帖子写得..."
- THEN fallback 走类目相关短句(从预置文案池挑一条)

#### Scenario: 空返回
- WHEN LLM 返回 "" 或仅空白
- THEN fallback 走预置文案池,保证非空

#### Scenario: 超长(> 200 字)
- WHEN LLM 返回 500 字长评
- THEN 截断到 200 字,加省略号

#### Scenario: persona 为空
- WHEN `persona` 为 null 或空字符串
- THEN prompt 中 persona 段落替换为 "性格随和的小镇居民,口语化,不端着"

### Requirement: LLM call MUST have a timeout safeguard
`generate(...)` MUST return within 8s. If the timeout fires, the generator MUST fall back to a curated pool of short Chinese comments and return a non-empty String.

#### Scenario: LLM 9s 未返回
- WHEN streamTextWithFallback 9s 仍没结果
- THEN 取消等待,fallback 文案,返回 String

### Requirement: Output quote marks MUST be stripped
If the LLM text starts or ends with `"` or `"`, those characters MUST be stripped from the output.

#### Scenario: 文本首尾是 "
- WHEN LLM 返回 `"下雪真好玩"`
- THEN strip 首尾的 `"`,返回 `下雪真好玩`

## Out of Scope

- 不缓存 LLM 结果(同 persona + 同帖每次都重新生成,确保多样性)
- 不限制 LLM 模型(后续可切到其他)
- 不支持多语言(本轮只中文)
