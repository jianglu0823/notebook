# AI伴侣智能日程与A2A协作系统 - 完整产品与技术需求文档

> **文档类型**: 产品需求文档 (PRD) + 技术开发实现规范 / AI Coding Context
> **产品负责人**: [产品经理]
> **核心架构**: Java Web (Spring Boot) + AgentScope + A2A Protocol
> **文档版本**: V1.0
> **最后更新**: 2026-08-06
> **文档状态**: 初稿评审

---

# 第一部分：产品需求文档 (PRD)

## 一、产品概述与核心差异

### 1.1 传统日程 vs 智能日程

| 维度 | 传统日程 | AI伴侣智能日程 |
|------|----------|----------------|
| **创建方式** | 手动选择时间、填写标题、勾选提醒 | 自然语言对话（如"明天下午帮我约个会"），AI自动解析 |
| **排期逻辑** | 用户自行查看空闲时间，手动避开冲突 | AI伴侣结合用户习惯、任务优先级、精力曲线智能推荐最优时段 |
| **执行闭环** | 仅到点提醒，缺乏过程管理 | 结合专注计时器、打卡确认、完成反馈，形成行为闭环 |
| **系统架构** | 单体应用，功能耦合 | 模块化设计，伴侣Agent与日程Agent通过A2A协议解耦通信 |

### 1.2 核心价值

- **对用户**：降低规划摩擦力，消除"启动障碍"，提供有温度的陪伴式时间管理。
- **对系统**：通过A2A协议实现能力解耦，日程Agent可独立迭代，未来可无缝接入第三方日历（如飞书、Apple Calendar）或其他业务Agent。

---

## 二、核心功能需求

### 2.1 多模态自然语言建日程

- **对话式创建**：用户在聊天界面发送语音或文字，AI伴侣自动提取关键要素（时间、地点、事件、参与人、重复规则）。
- **模糊语义解析**：支持"下周三下午"、"明天下雨就在家看书"等模糊表达，AI伴侣需进行意图确认。
- **深度思考排期**：当用户面临多目标冲突（如"周末既要写论文又要陪家人"），伴侣Agent调用日程Agent的"深度思考"能力，按紧急度和耗时预估生成带优先级（P0/P1/P2）的时间切片。

### 2.2 结构化执行与行为闭环

- **任务分类管理**：区分"每日习惯"（高频、自动化提醒，如按时吃药）与"弹性待办"（低压力家务，如整理床铺），避免认知混乱。
- **专注与打卡**：到达任务时间，伴侣不仅推送提醒，还可联动启动"专注计时器"。任务完成后需用户手动确认，生成连续打卡统计。
- **视觉化奖励**：为待办事项匹配主题Emoji，让清单生动化，对抗"清单疲劳"。

### 2.3 跨端同步与系统级提醒

- **日历双向同步**：支持将AI生成的日程一键导出为iCal格式，或同步至手机原生日历、飞书日历。
- **系统级通知**：穿透勿扰模式，支持锁屏横幅、闹钟提醒，确保重要行程不遗漏。

---

## 三、A2A 协议架构与通信机制

本系统采用 **Agent-to-Agent (A2A)** 协议进行跨Agent通信，确保伴侣Agent与日程Agent的解耦与标准化协作。

### 3.1 角色定义

- **Client Agent（伴侣聊天Agent）**：负责与用户交互，理解自然语言意图，作为任务发起者。
- **Server Agent（日程管理Agent）**：作为任务执行者，负责日历读写、冲突检测、排期算法。

### 3.2 核心通信流程

1. **能力发现（Discovery）**：伴侣Agent通过获取日程Agent的 **Agent Card**，了解其具备"创建日程"、"查询空闲"、"深度排期"等技能。
2. **任务提交（Task Submission）**：伴侣Agent将解析后的结构化数据封装为 A2A Task，发送给日程Agent。
3. **状态流转与协商（State & Negotiation）**：
   - 日程Agent返回 `working` 状态。
   - 若存在时间冲突，日程Agent返回 `input-required` 状态，伴侣Agent据此向用户发起追问（"明天下午3点有周会，改到4点可以吗？"）。
4. **结果交付（Artifact Delivery）**：日程Agent完成排期后，返回包含日程详情和确认卡片的 Artifact，伴侣Agent将其渲染为富文本消息卡片展示给用户。

---

## 四、交互与UI设计要求

### 4.1 聊天界面增强

- **意图识别下划线**：当AI伴侣在聊天中识别到日程创建意图时，使用"下划线"高亮提示，用户点击即可一键预览并确认创建。
- **富文本消息卡片**：日程创建成功/提醒时，采用卡片式UI（包含时间轴、事件名称、持续时长、操作按钮），而非纯文本。

### 4.2 独立日程管理面板

- 在应用内提供独立的【日程管家】入口，以可视化时间轴展示当日/本周安排。
- 支持拖拽调整时间、滑动完成打卡、长按编辑详情。

---

## 五、数据埋点与评估指标

| 指标类别 | 具体指标 | 目标值 |
|----------|----------|--------|
| **功能渗透** | 智能日程功能周活使用率 | ≥ 30% |
| **交互效率** | 对话建日程平均轮次 | ≤ 2 轮 |
| **转化效果** | 日程确认创建率（AI推荐→用户确认） | ≥ 75% |
| **执行闭环** | 日程打卡完成率 | ≥ 60% |
| **系统性能** | A2A 跨Agent通信平均延迟 | < 500ms |

---

## 六、风险与应对策略

| 风险 | 等级 | 应对策略 |
|------|------|----------|
| **隐私合规** | 高 | 日程数据涉及极高隐私，A2A通信必须采用双重授权机制，关键操作（如写入外部日历）需用户手动确认 |
| **大模型幻觉** | 高 | 时间解析错误导致建错日程。应对：所有AI生成的日程必须在UI上提供显眼的"修改/取消"按钮，并在创建前进行二次确认 |
| **多端同步延迟** | 中 | 采用Webhook+本地缓存双写机制，优先保障App内体验，后台异步同步至系统日历 |

---

# 第二部分：技术开发实现规范（AI Coding 专用）

> **目标读者**: AI Coding Assistant / 后端开发 / 架构师
> **核心架构**: Java Web (Spring Boot) + AgentScope + A2A Protocol
> **使用说明**: 将此文档交给 AI Coding 工具（如 Cursor, Windsurf, Copilot Workspace），按 Step 顺序逐步实现。

---

## 一、核心架构原则

### 1.1 Agent 解耦设计

系统必须严格遵循 **A2A (Agent-to-Agent)** 协议标准，禁止在伴侣 Agent 内部硬编码日程逻辑。

- **Companion Agent (Client)**: 负责 NLU、意图识别、多轮对话管理、UI 渲染。
- **Schedule Agent (Server)**: 负责日历 CRUD、冲突检测、排期算法、外部日历同步。
- **通信协议**: 基于 HTTP/SSE 的 JSON-RPC 2.0，遵循 A2A Task 生命周期。

### 1.2 技术栈约束

- **后端框架**: Spring Boot 3.x + Spring AI Alibaba
- **Agent 框架**: AgentScope Java SDK
- **注册中心**: Nacos (用于 Agent Card 服务发现)
- **存储**: PostgreSQL (业务数据) + Redis (会话/状态缓存)
- **本地执行**: 需设计 Local Agent Bridge 以支持 Mac 本地日历写入

---

## 二、A2A 通信协议规范

### 2.1 Agent Card 定义 (Schedule Agent)

Schedule Agent 启动时需向 Nacos 注册以下元数据：

```json
{
  "name": "schedule-agent-v1",
  "description": "负责用户日程管理、冲突检测、智能排期及本地日历同步",
  "url": "http://schedule-agent-service:8081/a2a",
  "capabilities": {
    "streaming": true,
    "pushNotifications": false
  },
  "skills": [
    {
      "id": "create_event",
      "name": "创建日程",
      "description": "根据自然语言描述创建日程，支持模糊时间解析",
      "inputSchema": { ... }
    },
    {
      "id": "smart_schedule",
      "name": "智能排期",
      "description": "基于用户习惯和空闲时间，生成最优时间切片方案",
      "inputSchema": { ... }
    }
  ]
}
```

### 2.2 核心交互流程 (Sequence)

1. **Intent**: 用户说 "明天下午帮我约个会，大概1小时"
2. **Parse**: Companion Agent 提取 `{action: "create", time: "tomorrow 14:00", duration: "1h", title: "会议"}`
3. **A2A Request**: Companion 发送 `tasks/send` 请求到 Schedule Agent。
4. **Conflict Check**: Schedule Agent 检查冲突。
   - *无冲突*: 返回 `TaskStatus: completed` + `Artifact: EventCard`。
   - *有冲突*: 返回 `TaskStatus: input-required` + `Message: "明天14:00已有周会，建议改到15:30，可以吗？"`
5. **Confirm**: Companion 展示卡片，用户点击确认/修改。
6. **Execute**: Companion 发送 `tasks/sendSubscribe` (若需流式) 或再次 `tasks/send` 确认执行。
7. **Local Sync**: Schedule Agent 异步调用 Local Bridge 写入 Mac 日历。

---

## 三、功能模块实现要求

### 3.1 模块 A: 日程 Agent (Server)

**核心职责**:

1. **NLU 结构化**: 将 "下周三下午" 转为 ISO8601 时间戳。
2. **排期算法**:
   - 输入: 任务列表 + 用户偏好 (如 "上午不排会")。
   - 输出: 推荐时间段列表 (Top 3)。
3. **本地执行器**:
   - 接口: `POST /local/execute`
   - 实现: 调用 AppleScript (`osascript`) 或 EventKit (via JNA) 操作 Mac 日历。
   - *安全约束*: 必须经过用户 OAuth 或本地 Token 授权。

**数据模型 (Event)**:

```java
public class ScheduleEvent {
    String id;
    String title;
    LocalDateTime startTime;
    LocalDateTime endTime;
    EventPriority priority; // P0, P1, P2
    String location;
    List<String> attendees;
    SyncStatus syncStatus; // PENDING, SYNCED, FAILED
}
```

### 3.2 模块 B: 伴侣 Agent (Client)

**核心职责**:

1. **Tool 注册**: 将 Schedule Agent 的 Skills 注册为 LangChain/AgentScope Tools。
2. **UI 渲染**:
   - 识别 `Artifact.type == "schedule_card"`。
   - 渲染为富文本卡片 (时间轴 + 确认按钮)。
3. **上下文记忆**: 记住用户的 "排期偏好" (如 "周五下午通常摸鱼")，作为 System Prompt 传给 Schedule Agent。

---

## 四、接口定义 (OpenAPI 3.0 风格)

### 4.1 A2A 标准端点

```yaml
paths:
  /a2a/tasks/send:
    post:
      summary: 发送任务给日程Agent
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TaskSendParams'
      responses:
        '200':
          description: 任务状态更新
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Task'

  /a2a/tasks/sendSubscribe:
    post:
      summary: 发送任务并订阅流式更新
      responses:
        '200':
          content:
            text/event-stream:
              schema:
                $ref: '#/components/schemas/TaskStatusUpdateEvent'
```

### 4.2 本地日历桥接端点 (Internal)

```yaml
paths:
  /internal/local-calendar/sync:
    post:
      summary: 同步日程到本地Mac日历
      security:
        - LocalAgentToken: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                action:
                  type: string
                  enum: [CREATE, UPDATE, DELETE]
                event:
                  $ref: '#/components/schemas/ScheduleEvent'
```

---

## 五、AI Coding 执行步骤 (Roadmap)

请 AI 按照以下顺序生成代码，**每一步完成后暂停等待确认**：

### Step 1: 基础模型与协议

- 定义 `Task`, `Message`, `Artifact`, `AgentCard` 等 A2A 核心 Java 实体类。
- 定义 `ScheduleEvent` 数据库实体及 Repository。

### Step 2: Schedule Agent 服务端

- 实现 `/a2a/tasks/send` 控制器。
- 实现 `SmartSchedulerService` (基于规则的简单排期，预留 LLM 接口)。
- 实现 `LocalCalendarBridge` (AppleScript 执行器，Mock 模式优先)。

### Step 3: Companion Agent 客户端

- 实现 `A2AClient`，封装 HTTP/SSE 调用逻辑。
- 实现 `ScheduleTool`，将 A2A 调用包装为 AgentScope Tool。
- 更新 System Prompt，注入日程管理指令。

### Step 4: 联调与测试

- 编写集成测试：模拟用户输入 "明天下午3点开会"，验证 A2A 流转及 Mock 日历写入。

---

## 六、关键约束与注意事项

- **隐私**: 日程数据必须加密存储，A2A 通信建议走 mTLS 或内部 Token 鉴权。
- **幂等性**: `create_event` 必须支持幂等 Key，防止网络重试导致重复建日程。
- **容错**: 若 Schedule Agent 宕机，Companion Agent 应降级为 "纯文本记录"，并提示用户 "日程服务暂时不可用，已为您记在备忘录"。
- **本地权限**: Mac 本地日历写入需引导用户授权 "自动化" 权限，代码中需处理 `Permission Denied` 异常并给出友好提示。

---

## 七、附录：Prompt 模板参考

### Schedule Agent System Prompt

```
你是专业的日程管理专家。你的任务是将用户的自然语言需求转化为结构化的日程事件。
规则：
1. 如果时间模糊，必须询问确认，不要瞎猜。
2. 检测到冲突时，主动提供 2-3 个替代方案。
3. 输出必须严格符合 A2A Artifact 格式。
4. 语气简洁专业，不要废话。
```

### Companion Agent 增强指令

```
当用户提到 "安排"、"提醒"、"明天"、"开会" 等词汇时，调用 `schedule_agent_tool`。
不要自己编造时间，必须依赖 Schedule Agent 的返回结果。
如果 Schedule Agent 返回 `input-required`，请将选项以按钮形式展示给用户。
```

---

# 第三部分：AI Coding 工具启动指令

---

## 给 AI Coding 工具的启动指令

复制以下内容发送给 AI Coding 工具（如 Cursor, Windsurf, Copilot Workspace）：

> "我已阅读完整的 PRD 文档。请基于 Spring Boot 3.x 和 AgentScope Java SDK，我们现在开始 **Step 1: 基础模型与协议**。
>
> 请生成以下代码：
> 1. A2A 协议核心实体类 (`Task`, `Message`, `Artifact`, `AgentCard`)。
> 2. `ScheduleEvent` 的 JPA Entity 和 Repository。
> 3. `A2AProtocolException` 自定义异常类。
>
> 要求：代码风格简洁，包含必要注释，使用 Lombok。"

---

## 后续 Step 提示词模板

### Step 2 提示词

> "请基于已完成的 A2A 核心实体，实现 Schedule Agent 服务端的以下模块：
> 1. `/a2a/tasks/send` 控制器，处理任务创建请求。
> 2. `SmartSchedulerService`，实现基于规则的简单排期逻辑（预留 LLM 接口）。
> 3. `LocalCalendarBridge`，使用 Mock 模式实现 AppleScript 调用框架。
>
> 要求：每个类包含完整的 JavaDoc 注释，异常处理完善。"

### Step 3 提示词

> "请实现 Companion Agent 客户端的以下模块：
> 1. `A2AClient`，封装 HTTP/SSE 调用 Schedule Agent 的逻辑。
> 2. `ScheduleTool`，将 A2A 调用包装为 AgentScope Tool，供伴侣 Agent 使用。
> 3. 更新伴侣 Agent 的 System Prompt，注入日程管理指令。
>
> 要求：A2AClient 需支持连接超时、重试机制和降级策略。"

### Step 4 提示词

> "请编写以下集成测试：
> 1. 模拟用户输入 '明天下午3点开会' 的完整 A2A 流转链路。
> 2. 验证 Schedule Agent 正确解析时间、检测冲突、返回任务结果。
> 3. 验证 Mock LocalCalendarBridge 被正确调用。
>
> 要求：使用 Mockito 和 TestContainers，测试覆盖正常流程和异常流程。"

---

> **文档结束**
> 下一步行动：组织研发评审 → 技术方案评审 → 设计稿输出 → 开发排期
> 如有疑问请联系产品负责人。
