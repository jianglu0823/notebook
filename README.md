# 鹿匠笔记(LuJiang Note)

一个类 Google **NotebookLM** 的应用:上传自己的资料(PDF / Word / 文本 / 图片 / 音频),基于这些资料做**带出处引用的 RAG 问答**,并能一键生成**摘要 / 学习指南 / FAQ**,以及**音频概览播客**(两位 AI 主持人对话式讲解)。

在此之上,我们把它从一个「知识库 + 创作工具」长成了一个**多智能体 AI 协作平台**:

- **📚 RAG 知识库**:资料摄取、向量化问答、文档生成、双主持人音频播客
- **✍️ 创作工作台**:新闻方向联网聚合、小红书文案流水线、多智能体协作写作(作者 / 核查员 / 主编)
- **🧑‍🤝‍🧑 智能体小世界「鹿匠小镇」**:有职业 / 人设 / 心情 / 金币的 AI 居民自主生活、创作、开会、恋爱婚育、老去离世;支持「沙盒快进」在隔离环境里推演几十年后的结局
- **💬 AI 伴侣**:像真人一样的「单角色陪伴式对话」,可创建多个伴侣、选底层模型、文字 + 图片 + 语音消息、TTS 推回浏览器播报、长期记忆「记得你」
- **📅 智能日程**(独立微服务 `schedule-agent`):自然语言创建日程、专注打卡 / streak、每日亮点复盘;与 AI 伴侣通过 A2A 协议联动

默认大模型**走智谱 GLM 免费模型**(`glm-4.7-flash` 文本、`glm-z1-flash` 推理、`cogview-3-flash` 文生图、`cogvideox-flash` 文生视频、`glm-4.6v-flash` 多模态),失败按序降级备用免费模型;可一键切换到**通义千问 / DashScope** 付费模型。微服务基础设施基于 **Spring Cloud + Nacos**,一键 Docker 部署。

---

## 截图 · 体验

> 首次访问会自动跳转**全屏登录页** —— 山湖背景 + 鹿匠笔记品牌 + 账号登录 / 注册 + 「以游客模式进入」入口。选过游客模式后,后续不再跳登录(本地存储标记)。

---

## 功能一览

| 能力 | 说明 |
|---|---|
| **资料摄取** | 上传 PDF / Word / TXT / 图片 / 音频,自动解析、分块、向量化入库 |
| **图像理解** | 图片经多模态模型(`qwen-vl-max` / `glm-4.6v-flash`)生成结构化描述后并入 RAG |
| **音频转写** | 音频经 Paraformer ASR 转写为文本后并入 RAG |
| **RAG 问答** | 基于所选笔记检索作答,SSE 流式返回,**带可点击出处引用** |
| **文档生成** | 一键生成摘要 / 学习指南 / FAQ(Markdown) |
| **音频概览** | 双主持人对话脚本 → CosyVoice 逐句合成 → 拼接为 MP3 |
| **新闻聚合** | 选方向 → 大模型 `enable_search` 联网检索最新动态 → 整理成笔记入库 |
| **小红书工作台** | 方向→标题→联网素材→风格文案→通义万相配图的多步创作流水线,支持一键配音短视频 |
| **多智能体写作** | 作者 / 核查员 / 主编三个 `ReActAgent` 迭代收敛,核查员自主联网核实,过程实时围观 |
| **智能体小世界** | 「鹿匠小镇」AI 居民自主生活、创作、婚育、老去离世;沙盒快进推演几十年 |
| **AI 伴侣** | 多伴侣 CRUD、12 预设模板、文字/图片/语音消息、TTS 推回播放、长期记忆、亲密度计数 |
| **智能日程** | 独立微服务;自然语言创建、专注打卡 / streak、每日复盘、**A2A 协议**与伴侣 / 智能体小镇联动 |
| **账号与隔离** | 自研 JWT + 游客模式,`owner_id` 数据隔离;内置只读「系统公告」笔记本 |
| **访问日志** | 记录登录 / 注册 / 写操作的 IP、设备类型、动作(见 `/api/logs`) |

---

## 系统架构

整体为**模块化单体 + 独立微服务 + 网关 + 注册中心**:
浏览器请求经 **Spring Cloud Gateway** 打到 `notebooklm-app`;`schedule-agent` 是独立 A2A 微服务(端口 8081),被 `notebooklm-app` 经反代集成;`AI 伴侣` 与 `智能体小镇` 是 `notebooklm-app` 内部模块,分别通过 A2A Client SDK 与 `schedule-agent` 联动。

```
                          ┌─────────────┐
                          │   浏览器 SPA │  原生 JS 单文件,应用直接托管
                          │  (登录页 / 主界面)
                          └──────┬──────┘
                                 │ HTTP / SSE / WebSocket
                          ┌──────▼───────────────┐
                          │ Spring Cloud Gateway │  :9000  (lb:// 服务发现路由)
                          └──────┬───────────────┘
                                 │  服务发现
         注册/配置    ┌───────────▼────────────────────────────────────────┐
      ┌──────────────┤             notebooklm-app  :8080                 │
      │   Nacos      │  模块化单体,JDK21 / Spring Boot 3.3                │
      │  :8848       ├────────────────────────────────────────────────────┤
      └──────────────┤ auth │ notebook │ note │ ingest │ qa                │
                     │  gen │ audio │ news │ studio                        │
                     │  world(智能体小镇) │ companion(AI 伴侣)             │
                     │  schedule(反代到 8081) │ log(访问日志)             │
                     └─────────────────────────────┬──────────────────────┘
                                                   │ /api/schedule/**
                                                   │ 反代 + X-User-Id / X-Schedule-Agent-Token
                                                   ▼
                                       ┌───────────────────────────┐
                                       │   schedule-agent  :8081    │  独立 Spring Boot 微服务
                                       │  A2A Server + REST + LLM   │  (Java 21 + SB 3.3.5)
                                       │  12 项日程能力 / 16 张表   │
                                       └─────────────┬─────────────┘
                                                     │
                向量检索 │   元数据 │   大模型  │   缓存/会话 │  日程元数据
                   ┌─────▼───┐ ┌────▼────┐ ┌───▼──────┐ ┌──▼─────┐ ┌──▼──────────┐
                   │ Milvus  │ │ MySQL 8 │ │ DashScope │ │ Redis 7│ │schedule-     │
                   │(etcd+   │ │ 3307    │ │ + 智谱GLM │ │        │ │agent-mysql   │
                   │ minio)  │ │(notebook│ │(免费优先  │ │        │ │ 3308         │
                   │         │ │/note/…) │ │/付费降级) │ │        │ │(schedule_    │
                   └─────────┘ └────────┘ └──────────┘ └────────┘ │ agent schema)│
                                                                   └─────────────┘
```

**摄取链路**:上传文件 / 笔记正文 → 解析(TikaReader,图片走多模态模型、音频走 Paraformer)→ 分块 → `text-embedding-v3` 向量化 → 写 Milvus(payload 带 `notebook_id / note_id / source_id / seq`)。
**问答链路**:检索 Milvus(按所选笔记过滤)→ 拼上下文 → 主模型生成 → SSE 流式返回并回填引用。
**A2A 链路**:`companion` / `world` 通过 `schedule-agent-client-sdk` 调 A2A Server,完成「助手提醒我明早 9 点开会」「小镇某居民今晚要做的待办」等联动。

---

## 技术栈

| 层次 | 技术选型 |
|---|---|
| 应用层 | **JDK 21**、**Maven 3.9+**、**Spring Boot 3.3.5**、**Spring Cloud 2023.0.3** + **Spring Cloud Alibaba 2023.0.1.3**(Nacos 配置 + 注册)、**Spring Cloud Gateway**(`lb://` 路由)、**JPA / Hibernate** |
| 智能体框架 | **AgentScope Java `2.0.0-RC4`**(`io.agentscope`):`SimpleKnowledge` + `MilvusStore` + `TikaReader`(RAG 检索/解析)、`DashScopeChatModel`(对话/视觉)、`DashScopeTextEmbedding`(向量化) |
| 大模型 / SDK | **默认智谱 GLM 免费模型**(OpenAI 兼容):文本 `glm-4.7-flash`、推理 `glm-z1-flash`、文生图 `cogview-3-flash`、文生视频 `cogvideox-flash`、多模态 `glm-4.6v-flash`,主模型失败按序降级备用免费模型;<br>**可选 DashScope 付费**:对话 `qwen-plus`/`qwen-flash`、图像理解 `qwen-vl-max`、文本向量 `text-embedding-v3`(1024 维)、语音识别 `paraformer-realtime-v2`、语音合成 `cosyvoice-v1`(多音色) |
| 存储 / 基础设施 | **MySQL 8** × 2(主库元数据 + `schedule_agent` 独立 schema)、**Redis 7**(缓存/会话)、**Milvus 2.x**(向量库,伴生 etcd + minio)、**Nacos 2.4.3**(配置 + 注册中心)—— 全部 Docker |
| 微服务 / A2A | **schedule-agent**(Java 21 + SB 3.3.5,Maven 三模块 `contract / server / client-sdk`):12 项日程能力、JSON-RPC A2A Server、AES-256-GCM 字段加密、Append-only 审计、Prometheus 指标 |
| 鉴权 / 日志 | 自研 **JWT**(jjwt + BCrypt)+ 游客模式,`owner_id` 账号隔离;登录 / 操作访问日志(IP / 设备类型 / 动作) |
| 前端 | 单文件 **原生 JS SPA**(`notebooklm-app/src/main/resources/static/index.html`),应用直接托管;**全屏登录页**(背景图 + 品牌卡 + 游客模式入口),无独立构建步骤 |

---

## 目录结构

```
LLM-note/
├── docker-compose.yml          # 全栈编排:nacos/mysql/redis/milvus + app + gateway
├── .env / .env.example         # DASHSCOPE_API_KEY / ZHIPU_API_KEY、各组件账号密码、暴露端口
├── infra/
│   ├── mysql/init.sql          # 建库建表(容器首启自动执行)
│   └── maven-settings.xml      # Docker 构建时用的 aliyun 镜像源
├── gateway/                    # Spring Cloud Gateway 模块
│   ├── pom.xml
│   ├── Dockerfile              # 多阶段构建
│   └── src/main/...
├── notebooklm-app/             # 单体应用(主要功能)
│   ├── pom.xml
│   ├── Dockerfile              # 多阶段构建
│   └── src/main/java/io/llmnote/
│       ├── config/             # AgentScope/DashScope/Milvus/CORS 配置
│       ├── auth/               # 自研 JWT + 游客模式,owner_id 账号隔离
│       ├── notebook/           # 笔记本 CRUD
│       ├── note/               # 笔记层(富文本正文 + 上传文件)
│       ├── ingest/             # 上传→解析→分块→向量化;图像理解;音频转写
│       ├── qa/                 # RAG 问答 + 引用(SSE 流式)
│       ├── gen/                # 摘要 / 学习指南 / FAQ 生成
│       ├── audio/              # 播客脚本生成 + TTS 合成
│       ├── news/               # 新闻方向联网聚合
│       ├── studio/             # 创作工作台:小红书文案流水线 + 多智能体协作写作
│       ├── world/              # 智能体小世界「鹿匠小镇」:居民/每日结算/会议/沙盒快进/关系经济/创作产物
│       ├── companion/          # AI 伴侣:多角色 CRUD / 多模态流式聊天 / 长期记忆 / 亲密度 / TTS 推回
│       ├── schedule/           # 智能日程反代(/api/schedule/** → 8081)+ X-User-Id / Token 注入
│       ├── llm/                # 多后端模型工厂:智谱 GLM 免费 + DashScope 付费,含降级重试与 token→元 费用换算
│       ├── log/                # 登录 / 操作访问日志
│       └── bootstrap/          # 启动时植入内置「系统公告」笔记本(项目架构 + 5 个专题)
└── schedule-agent/             # 独立 A2A 微服务(端口 8081,12 项日程能力)
    ├── pom.xml                 # 顶级父 POM
    ├── Dockerfile
    ├── docker-compose.yml      # schedule-agent + 独立 MySQL 3308(schedule_agent schema)
    ├── schedule-agent-contract/      # A2A 协议 + 业务 DTO + 错误码(零 Spring 依赖)
    ├── schedule-agent-server/        # Spring Boot 主微服务(A2A Server + REST + LLM + 日历桥)
    ├── schedule-agent-client-sdk/    # A2A 客户端 SDK(notebooklm-app 通过 maven 依赖接入)
    └── README.md
```

> 架构演进:从「模块化单体」演化为「**模块化单体 + 独立 A2A 微服务**」的混合形态。`schedule-agent` 是首个独立出去的能力(对安全 / 加密 / 审计要求最高,且需要单独扩缩容),`notebooklm-app` 继续通过 Nacos 服务发现 + 反代 + 客户端 SDK 消费其能力。

---

## 部署方式

### 前置条件
- 已安装 Docker + Docker Compose
- 一个 **智谱 GLM API Key**(免费,申请:https://open.bigmodel.cn/)
- 可选:一个 **DashScope API Key**(付费,申请:https://dashscope.console.aliyun.com/)

### 1. 配置环境变量

复制模板并填入真实 Key:

```bash
cp .env.example .env
# 编辑 .env,至少填写 ZHIPU_API_KEY
```

`.env` 关键项:

```dotenv
# 大模型(免费走智谱,付费走 DashScope)
ZHIPU_API_KEY=xxx.xxx                # 必填
DASHSCOPE_API_KEY=sk-xxxxxxxx        # 可选(无则仅用免费模型)

# MySQL(主库)
MYSQL_ROOT_PASSWORD=root123456
MYSQL_DATABASE=notebooklm

# Redis / Nacos
REDIS_PASSWORD=redis123456
NACOS_AUTH_TOKEN=...                 # Nacos 鉴权

# 暴露端口
APP_PORT=8080                        # 应用对外端口
GATEWAY_PORT=9000                    # 网关对外端口
```

> `.env` 已在 `.gitignore` 中,不会提交。

### 2. 启动主栈(notebooklm-app + 网关 + 基础设施)

```bash
set -a && . ./.env && set +a
docker compose up -d --build
```

首次构建会在容器内编译两个 Maven 项目并下载依赖,耗时数分钟。基础设施容器(mysql/nacos/redis/milvus)通过 healthcheck 就绪后,app 与 gateway 才会启动。

### 3. 启动 schedule-agent 独立微服务

```bash
cd schedule-agent

# 生成 AES 加密密钥(32 字节 Base64)与内部 token
export SCHEDULE_AGENT_ENC_KEY=$(openssl rand -base64 32)
export SCHEDULE_AGENT_INTERNAL_TOKEN=$(openssl rand -hex 32)

docker compose up -d --build
# 启动: schedule-agent-mysql(:3308) + schedule-agent(:8081)
```

> `schedule-agent` 的 MySQL 与主库**物理隔离**(独立 schema `schedule_agent`),`title/description/location` 等敏感字段以 **AES-256-GCM** 落库;`/api/schedule/v1/internal/*` 需带 `X-Schedule-Agent-Token` 头。

### 4. 验证

```bash
# 主应用健康检查
curl http://localhost:8080/actuator/health

# 网关健康检查
curl http://localhost:9000/actuator/health

# schedule-agent 健康检查
curl http://localhost:8081/actuator/health

# schedule-agent A2A AgentCard
curl http://127.0.0.1:8081/a2a/v1/agent-card | jq
```

浏览器打开 **http://localhost:9000**(经网关)或 **http://localhost:8080**(直连应用)即可使用完整界面。

### 常用运维命令

```bash
# 主栈
docker compose ps
docker compose logs -f app
docker compose logs -f gateway
docker compose up -d --build app            # 仅重建应用
docker compose down                         # 停止(保留数据卷)
docker compose down -v                      # 停止并清空数据卷(慎用)

# schedule-agent 独立栈
cd schedule-agent
docker compose ps
docker compose logs -f schedule-agent
docker compose up -d --build                # 重建
```

### 本地开发(不走 Docker 打包应用)

基础设施用 Docker,应用与微服务用 `spring-boot:run` 跑本机,便于热调试:

```bash
# 主栈基础设施
docker compose up -d nacos mysql redis milvus
set -a && . ./.env && set +a
cd notebooklm-app && mvn -o spring-boot:run

# schedule-agent(另开一个终端)
cd schedule-agent
SCHEDULE_AGENT_ENC_KEY=$(openssl rand -base64 32) mvn -o spring-boot:run -pl schedule-agent-server
```

---

## 使用文档

### 界面操作(推荐)

打开 http://localhost:9000,**首次访问会弹出全屏登录页** —— 账号登录 / 注册,或点「以游客模式进入」直接体验(数据存浏览器 cookie)。

登录后,左侧主导航涵盖四大区:

| 区 | 视图 | 关键能力 |
|---|---|---|
| **知识库** | 笔记本 / 笔记 / 问答 / 生成 / 播客 | 资料摄取、RAG 问答、文档生成、音频概览 |
| **创作** | 新闻聚合 / 小红书工作台 / 多智能体写作 | 三条创作流水线 |
| **AI 协作** | 智能体小镇 / **AI 伴侣** / **智能日程** | 三类智能体互动 |
| **系统** | 访问日志 / 主题切换 | 运维与个性化 |

#### 知识库
顶部四个标签页:
1. **资料** —— 上传文件(PDF/Word/TXT/图片/音频),列表实时轮询摄取状态(`PENDING → PARSING → DONE/FAILED`)。
2. **问答** —— 输入问题,答案流式返回,末尾展示引用来源(角标 + 片段)。
3. **生成** —— 一键生成摘要 / 学习指南 / FAQ,状态轮询,完成后展示 Markdown。
4. **播客** —— 一键生成音频概览,约 1–2 分钟,完成后可在线播放并展开对话脚本。

#### 智能体小世界「鹿匠小镇」
- 顶部「自主行动」开关:开启后小镇按设定间隔自动往前跑。
- 选居民看人设 / 心情 / 金币 / 技能;开 1:1 对话、围观会议;触发「沙盒快进」在隔离环境里推演几十年。
- 居民会老去离世,推理模型(`glm-z1-flash`)会为每位逝者生成一生回顾悼词。

#### AI 伴侣
- 左栏选 / 新建伴侣(12 个预设模板:温柔学姐 / 霸道总裁 / 元气少女 / 清冷剑仙 / 毒舌损友 / 优雅贵妇 / 全能管家 / 猫耳女仆 / 学术导师 / 摇滚歌手 / 魔法师 / 职场前辈)。
- 中间聊天区:文字 + 图片 + 语音消息,LLM 回复实时打字机效果 + 可选 TTS 推回播放。
- 每轮对话**异步抽取长期记忆**写入 Milvus,下次聊天时检索拼进 system prompt → 伴侣「记得」你。
- 顶部显示亲密度(消息数 + 互动天数,日上限 50)。

#### 智能日程
- 顶部「今日」看板:今日日程 / 专注中 / 今日复盘。
- 自然语言创建日程(LLM 解析时间 / 标题 / 地点),专注打卡累积 streak(连续天数)、里程碑提醒(7/30/100 天)。
- 走 `schedule-agent` 独立微服务,通过 `/api/schedule/**` 反代调用。

### REST API

所有接口均可经网关(`:9000`)或直连应用(`:8080`)访问。`/api/schedule/**` 路由会自动反代到 `schedule-agent`(:8081)。以下以网关为例。

#### 笔记本
```bash
# 列表
curl http://localhost:9000/api/notebooks

# 新建
curl -X POST http://localhost:9000/api/notebooks \
  -H "Content-Type: application/json" \
  -d '{"name":"我的笔记本","description":"可选描述"}'

# 详情 / 删除
curl http://localhost:9000/api/notebooks/1
curl -X DELETE http://localhost:9000/api/notebooks/1

# 该笔记本下的资料列表
curl http://localhost:9000/api/notebooks/1/sources
```

#### 资料上传(异步摄取)
```bash
curl -X POST http://localhost:9000/api/notebooks/1/sources \
  -F "file=@/path/to/doc.pdf"
# 立即返回 PENDING 状态的 Source,轮询 sources 列表查看 DONE
```
支持类型按扩展名判定:PDF / DOC(X) / TXT·MD / 图片(png·jpg·jpeg·webp) / 音频(mp3·wav·m4a·flac)。

#### RAG 问答(SSE 流式)
```bash
curl -N -X POST http://localhost:9000/api/notebooks/1/qa \
  -H "Content-Type: application/json" \
  -d '{"question":"这份资料讲了什么?","sessionId":null}'
```
返回事件序列:
- `session`:会话 ID(下次带上可延续会话)
- `citations`:JSON 引用数组(index / sourceName / snippet 等)
- `delta`:逐段增量文本
- `done`:结束

会话历史:
```bash
curl "http://localhost:9000/api/notebooks/1/qa/history?sessionId=<id>"
```

#### 文档生成(异步)
```bash
# kind: SUMMARY / STUDY_GUIDE / FAQ
curl -X POST http://localhost:9000/api/notebooks/1/docs/SUMMARY

curl http://localhost:9000/api/notebooks/1/docs        # 列表
curl http://localhost:9000/api/notebooks/1/docs/1      # 单个(轮询状态)
```

#### 音频概览播客(异步)
```bash
curl -X POST http://localhost:9000/api/notebooks/1/podcasts    # 触发生成
curl http://localhost:9000/api/notebooks/1/podcasts            # 列表
curl http://localhost:9000/api/notebooks/1/podcasts/1          # 状态轮询

# 状态 DONE 后播放/下载 MP3
curl http://localhost:9000/api/notebooks/1/podcasts/1/audio -o podcast.mp3
```
状态流转:`PENDING → SCRIPTING → SYNTHESIZING → DONE`(失败为 `FAILED`,附 `errorMsg`)。

#### 新闻聚合(异步)
```bash
# 提交一个新闻方向,联网检索并整理成笔记
curl -X POST http://localhost:9000/api/news \
  -H "Content-Type: application/json" \
  -d '{"topic":"AI 大模型"}'

curl http://localhost:9000/api/news          # 我的任务列表
curl http://localhost:9000/api/news/1        # 状态轮询(DONE 后带生成的 notebookId/noteId)
```

#### 小红书文案工作台(多步异步)
```bash
# Step1 方向 → 候选标题
curl -X POST http://localhost:9000/api/studio/xhs \
  -H "Content-Type: application/json" -d '{"direction":"秋季通勤穿搭"}'
curl http://localhost:9000/api/studio/xhs/1                 # 轮询到 TITLES_DONE

curl -X POST http://localhost:9000/api/studio/xhs/1/research \
  -H "Content-Type: application/json" -d '{"title":"选定的标题"}'   # Step2 联网素材
curl -X POST http://localhost:9000/api/studio/xhs/1/copy \
  -H "Content-Type: application/json" -d '{"style":"ZHONGCAO"}'    # Step3 风格文案
curl -X POST http://localhost:9000/api/studio/xhs/1/images         # Step4 通义万相配图

curl http://localhost:9000/api/studio/xhs/1/images/0 -o img0.png   # 取配图
curl -X PUT http://localhost:9000/api/studio/xhs/1/publish \
  -H "Content-Type: application/json" -d '{"publishStatus":"READY"}'
```
状态流转:`NEW → TITLES_DONE → RESEARCH_DONE → COPY_DONE → IMAGES_DONE`;风格枚举 `ZHONGCAO / DUSHE / GANHUO / ZHIYU`(种草 / 毒舌 / 干货 / 治愈)。

#### 多智能体协作写作(异步)
```bash
# 提交主题;体裁 ARTICLE/STORY/REVIEW/SCRIPT,maxRounds 1~5
curl -X POST http://localhost:9000/api/studio/writing \
  -H "Content-Type: application/json" \
  -d '{"topic":"介绍杭州的秋天","genre":"ARTICLE","maxRounds":3}'

curl http://localhost:9000/api/studio/writing            # 我的创作列表
curl http://localhost:9000/api/studio/writing/1          # 轮询:events 事件时间线 + rounds 每轮过程 + finalText 终稿
```
状态流转:`PENDING → RUNNING → DONE`(失败为 `FAILED`)。作者写初稿,每轮核查员自主联网核实 + 主编 `VERDICT: APPROVE/REVISE` 裁决,收敛或用尽轮数即定稿;`events` 实时暴露核查员搜索的关键词与事实预览,前端可「围观」协作过程。

#### 智能体小世界(智能体小镇)
```bash
# 居民
curl http://localhost:9000/api/world/employees
curl -X POST http://localhost:9000/api/world/employees \
  -H "Content-Type: application/json" -d '{"name":"林黛玉","emojiAvatar":"🌸","profession":"作家",...}'

# 触发每日结算
curl -X POST http://localhost:9000/api/world/tick

# 1:1 对话
curl -X POST http://localhost:9000/api/world/employees/1/chat \
  -H "Content-Type: application/json" -d '{"message":"你今天在做什么?"}'

# 沙盒快进
curl -X POST http://localhost:9000/api/world/sandbox \
  -H "Content-Type: application/json" -d '{"years":30,"seedEmployeeIds":[1,2,3]}'
```

#### AI 伴侣
```bash
# 伴侣列表
curl http://localhost:9000/api/companion

# 新建伴侣
curl -X POST http://localhost:9000/api/companion \
  -H "Content-Type: application/json" \
  -d '{"name":"温柔学姐","gender":"F","personaTags":["温柔","知性"],"age":22,"intro":"中文系大四学姐","voiceStyle":"sweet"}'

# 上传图片(供伴侣识别)
curl -X POST http://localhost:9000/api/companion/chat/upload \
  -F "file=@/path/to/img.jpg" -F "companionId=1"

# WebSocket 流式聊天(协议见 companion/ws/CompanionWsHandler.java)
# wscat -c ws://localhost:9000/ws/companion -H "Cookie: nblm_token=<jwt>"
```

#### 智能日程(经反代到 schedule-agent :8081)
```bash
# 自然语言创建日程(LLM 解析)
curl -X POST http://localhost:9000/api/schedule/v1/events \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"natural":"明早 9 点跟张三开周会,讨论 Q3 OKR"}'

# 今日日程
curl "http://localhost:9000/api/schedule/v1/today?userId=1"

# 开始专注打卡
curl -X POST http://localhost:9000/api/schedule/v1/focus/start \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"eventId":"<event-id>","plannedDurationMinutes":25}'

# 心跳 / 完成
curl -X POST http://localhost:9000/api/schedule/v1/focus/<sessionId>/heartbeat -d '{}'
curl -X POST http://localhost:9000/api/schedule/v1/focus/<sessionId>/complete \
  -H "Content-Type: application/json" -d '{"reflection":"完成了 25 分钟"}'
```

---

## 端口一览

| 服务 | 容器 | 宿主端口 | 备注 |
|---|---|---|---|
| 网关 | `nblm-gateway` | `9000` | 经网关访问主应用 |
| 应用 | `nblm-app` | `8080` | 直连主应用 |
| Nacos | `nblm-nacos` | `8848` / `9848` | 配置 + 注册中心 |
| MySQL(主) | `nblm-mysql` | `3307` | 数据库 `notebooklm` |
| Redis | `nblm-redis` | `6379` | 缓存 / 会话 |
| Milvus | `nblm-milvus` | `19530` / `9091` | 向量库 |
| **schedule-agent** | `schedule-agent` | `8081` | 独立 A2A 微服务 |
| **schedule-agent-mysql** | `schedule-agent-mysql` | `3308` | 数据库 `schedule_agent`(物理隔离) |

---

## 项目里程碑

| 时间 | 里程碑 |
|---|---|
| 2025 | RAG 知识库 + 摘要/学习指南/FAQ + 双主持人音频播客 |
| 2026-Q1 | 创作工作台:新闻聚合 + 小红书多步流水线 + 多智能体协作写作 |
| 2026-Q2 | 智能体小世界「鹿匠小镇」:居民/每日结算/婚育/沙盒快进 |
| 2026-Q3 | **AI 伴侣**(多角色 + 多模态 + 长期记忆 + 亲密度)+ 全屏登录页 + 智能日程独立微服务(schedule-agent) |

---

## 常见问题

- **首次访问跳到登录页**:默认行为;点「以游客模式进入」后,后续不再跳转(本地存储标记)。要重新跳登录,清浏览器 localStorage 里的 `nblm_guest_chosen`。
- **登录后端返回 401**:JWT 过期或失效,会清掉本地 `nblm_token` 并重新弹登录页。
- **网关返回 503 `No servers available`**:应用刚启动时 LoadBalancer 缓存尚未预热,稍等几秒重试即可;若持续,检查应用是否已注册到 Nacos(`docker compose logs app | grep register`)。
- **应用启动报 apiKey required**:`.env` 中 `ZHIPU_API_KEY`(免费)或 `DASHSCOPE_API_KEY`(付费)未填或未 `set -a` 导出。
- **上传后一直 FAILED**:查看该资料 `errorMsg`,常见为文件解析失败或模型调用异常;`docker compose logs -f app` 看详细堆栈。
- **MySQL 端口冲突**:主库对外映射 `3307`,`schedule-agent` 库映射 `3308`,均避开本机 3306;容器内互访仍是 3306。
- **`schedule-agent` 启动报 `SCHEDULE_AGENT_ENC_KEY env var is required`**:`.env` 缺失或未 `set -a` 导出 32 字节 Base64 AES 密钥,用 `openssl rand -base64 32` 生成。
- **`/api/schedule/v1/internal/*` 返回 403/401**:`X-Schedule-Agent-Token` 头缺失或与 `schedule-agent` 容器内 `SCHEDULE_AGENT_INTERNAL_TOKEN` 不一致。
