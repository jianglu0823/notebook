# NotebookLM 自建版

一个类 Google **NotebookLM** 的应用:上传自己的资料(PDF / Word / 文本 / 图片 / 音频),基于这些资料做**带出处引用的问答**,并能一键生成**摘要 / 学习指南 / FAQ**,以及**音频概览播客**(两位 AI 主持人对话式讲解)。

全部大模型能力统一走**通义千问 / DashScope**,微服务基础设施基于 **Spring Cloud + Nacos**,一键 Docker 部署。

---

## 功能一览

| 能力 | 说明 |
|---|---|
| 资料摄取 | 上传 PDF / Word / TXT / 图片 / 音频,自动解析、分块、向量化入库 |
| 图像理解 | 图片经 `qwen-vl-max` 生成结构化描述后并入 RAG |
| 音频转写 | 音频经 Paraformer ASR 转写为文本后并入 RAG |
| RAG 问答 | 基于笔记本资料检索作答,SSE 流式返回,**带可点击出处引用** |
| 文档生成 | 一键生成摘要 / 学习指南 / FAQ(Markdown) |
| 音频概览 | qwen 生成双主持人对话脚本 → CosyVoice 逐句合成 → 拼接为 MP3 |

---

## 技术栈

### 应用层
- **JDK 21**、**Maven 3.9+**
- **Spring Boot 3.3.5**
- **Spring Cloud 2023.0.3** + **Spring Cloud Alibaba 2023.0.1.3**(Nacos 配置 + 注册)
- **Spring Cloud Gateway**(网关,`lb://` 服务发现路由)
- **JPA / Hibernate**(元数据持久化)

### 智能体与大模型(AgentScope Java v2 + DashScope)
- **AgentScope Java `2.0.0-RC4`**(`io.agentscope`)
  - `SimpleKnowledge` + `MilvusStore` + `TikaReader`(RAG 检索/解析)
  - `DashScopeChatModel`(对话 / 视觉)、`DashScopeTextEmbedding`(向量化)
- **DashScope SDK `2.22.9`**:Paraformer(ASR)、CosyVoice / `ttsv2`(TTS)
- 模型清单:
  | 用途 | 模型 |
  |---|---|
  | 文本对话 | `qwen-plus` |
  | 图像理解 | `qwen-vl-max` |
  | 文本向量 | `text-embedding-v3`(1024 维) |
  | 语音识别 | `paraformer-realtime-v2` |
  | 语音合成 | `cosyvoice-v1`(音色 A `longwan` / B `longcheng`) |

### 存储与基础设施(全部 Docker)
- **MySQL 8**:元数据(notebook / source / chunk / qa_history / generated_doc / podcast)
- **Redis 7**:缓存 / 会话
- **Milvus 2.x**:向量库(伴生 etcd + minio)
- **Nacos 2.4.3**:配置中心 + 注册中心

### 前端
- 单文件 **原生 JS SPA**(`notebooklm-app/src/main/resources/static/index.html`),由应用直接托管,无独立构建步骤。

---

## 目录结构

```
LLM-note/
├── docker-compose.yml          # 全栈编排:nacos/mysql/redis/milvus + app + gateway
├── .env / .env.example         # DASHSCOPE_API_KEY、各组件账号密码、暴露端口
├── infra/
│   ├── mysql/init.sql          # 建库建表(容器首启自动执行)
│   └── maven-settings.xml      # Docker 构建时用的 aliyun 镜像源
├── gateway/                    # Spring Cloud Gateway 模块
│   ├── pom.xml
│   ├── Dockerfile              # 多阶段构建
│   └── src/main/...
└── notebooklm-app/             # 单体应用(第一阶段含全部功能)
    ├── pom.xml
    ├── Dockerfile              # 多阶段构建
    └── src/main/java/io/llmnote/
        ├── config/             # AgentScope/DashScope/Milvus/CORS 配置
        ├── notebook/           # 笔记本 & 资料 CRUD
        ├── ingest/             # 上传→解析→分块→向量化;图像理解;音频转写
        ├── qa/                 # RAG 问答 + 引用(SSE 流式)
        ├── gen/                # 摘要 / 学习指南 / FAQ 生成
        ├── audio/              # 播客脚本生成 + TTS 合成
        └── llm/                # DashScopeChatModel 阻塞式封装
```

> 架构演进:当前为**模块化单体**,但从第一天即接入 Nacos。后续可按 `notebook / ingest / qa / gen / audio` 五个内部模块平滑拆成独立微服务,网关与注册中心已就位。

---

## 部署方式

### 前置条件
- 已安装 Docker + Docker Compose
- 一个 **DashScope API Key**(申请:https://dashscope.console.aliyun.com/)

### 1. 配置环境变量

复制模板并填入真实 Key:

```bash
cp .env.example .env
# 编辑 .env,至少填写 DASHSCOPE_API_KEY
```

`.env` 关键项:

```dotenv
DASHSCOPE_API_KEY=sk-xxxxxxxx      # 必填
MYSQL_ROOT_PASSWORD=root123456
MYSQL_DATABASE=notebooklm
REDIS_PASSWORD=redis123456
APP_PORT=8080                      # 应用对外端口
GATEWAY_PORT=9000                  # 网关对外端口
```

> `.env` 已在 `.gitignore` 中,不会提交。

### 2. 一键启动全栈

```bash
set -a && . ./.env && set +a
docker compose up -d --build
```

首次构建会在容器内编译两个 Maven 项目并下载依赖,耗时数分钟。基础设施容器(mysql/nacos/redis/milvus)通过 healthcheck 就绪后,app 与 gateway 才会启动。

### 3. 验证

```bash
# 应用健康检查
curl http://localhost:8080/actuator/health

# 网关健康检查
curl http://localhost:9000/actuator/health

# 经网关访问 API
curl http://localhost:9000/api/notebooks
```

浏览器打开 **http://localhost:9000**(经网关)或 **http://localhost:8080**(直连应用)即可使用完整界面。

### 常用运维命令

```bash
docker compose ps                    # 查看容器状态
docker compose logs -f app           # 跟踪应用日志
docker compose logs -f gateway       # 跟踪网关日志
docker compose up -d --build app     # 仅重建并重启应用
docker compose down                  # 停止全部(保留数据卷)
docker compose down -v               # 停止并清空数据卷(慎用)
```

### 本地开发(不走 Docker 打包应用)

基础设施用 Docker,应用用 `spring-boot:run` 跑本机,便于热调试:

```bash
docker compose up -d nacos mysql redis milvus   # 只起基础设施
set -a && . ./.env && set +a                    # spring-boot:run 不会自动加载 .env
cd notebooklm-app && mvn -o spring-boot:run
```

---

## 使用文档

### 界面操作(推荐)

打开 http://localhost:9000,左侧新建笔记本,顶部四个标签页:

1. **资料** —— 上传文件(PDF/Word/TXT/图片/音频),列表实时轮询摄取状态(`PENDING → PARSING → DONE/FAILED`)。
2. **问答** —— 输入问题,答案流式返回,末尾展示引用来源(角标 + 片段)。
3. **生成** —— 一键生成摘要 / 学习指南 / FAQ,状态轮询,完成后展示 Markdown。
4. **播客** —— 一键生成音频概览,约 1–2 分钟,完成后可在线播放并展开对话脚本。

### REST API

所有接口均可经网关(`:9000`)或直连应用(`:8080`)访问。以下以网关为例。

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

---

## 端口一览

| 服务 | 容器 | 宿主端口 |
|---|---|---|
| 网关 | nblm-gateway | `9000` |
| 应用 | nblm-app | `8080` |
| Nacos | nblm-nacos | `8848` |
| MySQL | nblm-mysql | `3307`(容器内 3306) |
| Redis | nblm-redis | `6379` |
| Milvus | nblm-milvus | `19530` / `9091` |

---

## 常见问题

- **网关返回 503 `No servers available`**:应用刚启动时 LoadBalancer 缓存尚未预热,稍等几秒重试即可;若持续,检查应用是否已注册到 Nacos(`docker compose logs app | grep register`)。
- **应用启动报 apiKey required**:`.env` 中 `DASHSCOPE_API_KEY` 未填或未 `set -a` 导出。
- **上传后一直 FAILED**:查看该资料 `errorMsg`,常见为文件解析失败或 DashScope 调用异常;`docker compose logs -f app` 看详细堆栈。
- **MySQL 端口冲突**:对外映射为 `3307`,避免与本机 3306 冲突;容器内互访仍是 3306。
