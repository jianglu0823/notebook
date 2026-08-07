# Spec: companion-intimacy

基础亲密度计数 + 互动天数 + 7/30/100 天里程碑提示。极简版：只计数不"解锁"新能力。

## ADDED Requirements

### Requirement: 亲密度初始化

创建伴侣时系统 MUST 在 `companion_intimacy` 表新增一行：`companion_id`（唯一）、`score=伴侣.intimacyStart`（默认 30）、`daysCount=0`、`lastInteractDate=null`、`milestone7Sent=false`、`milestone30Sent=false`、`milestone100Sent=false`。

#### Scenario: 新建伴侣初始化亲密度
- WHEN 用户从模板 `gentle_senior` 创建伴侣（`intimacyStart=40`）
- THEN `companion_intimacy.score=40`，`daysCount=0`

### Requirement: 消息计数加分

每条 `companion_message.role=user` 入库时，系统 MUST：
1. 累计今日加分（每日上限 +50），超出不再加。
2. `score += delta`。
3. 若 `lastInteractDate=null` 或 `lastInteractDate<today`，`daysCount+=1`，`lastInteractDate=today`。
4. 检查 `daysCount` 是否命中 7/30/100 且对应 `milestone*Sent=false`；若是，向 `companion_message` 追加一行 `role=system, content="我们已经认识 7 天啦~"`（或 30/100）并把 `milestone*Sent=true`。

#### Scenario: 首次消息触发首次互动
- WHEN 用户对某伴侣发首条消息（`daysCount=0`）
- THEN `daysCount=1`，`lastInteractDate=today`，`score=start+1`

#### Scenario: 同日多次消息累计
- WHEN 用户在同日发 60 条消息
- THEN `score` 累计 +50（封顶），不再加

#### Scenario: 跨日第二次进入触发 +1 天
- WHEN `lastInteractDate=昨天`，用户今日发消息
- THEN `daysCount+=1`，`lastInteractDate=today`

#### Scenario: 7 天里程碑
- WHEN `daysCount` 从 6 变为 7
- THEN 追加 system 消息"我们已经认识 7 天啦~"
- AND `milestone7Sent=true`

#### Scenario: 30/100 天里程碑
- WHEN `daysCount` 变为 30 或 100
- THEN 追加对应 system 消息，`milestone30Sent=true` / `milestone100Sent=true`

### Requirement: 亲密度只读 API

GET `/api/companion/{id}/intimacy` MUST 返回 `{score, daysCount, lastInteractDate, milestone7Sent, milestone30Sent, milestone100Sent}`。任一不存在/不属于当前用户的伴侣 MUST 返回 404。

#### Scenario: 拉取亲密度
- WHEN 用户 GET
- THEN 200 返回亲密度六字段

### Requirement: 亲密度展示

前端 `#companionView` 聊天区顶部 MUST 显示 `score`（含"亲密度:"前缀）和"已认识 N 天"。

#### Scenario: 前端展示
- WHEN 亲密度 API 返回 `score=85, daysCount=12`
- THEN 顶部显示"亲密度: 85 | 已认识 12 天"
