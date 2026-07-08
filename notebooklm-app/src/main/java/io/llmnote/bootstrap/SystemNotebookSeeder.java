package io.llmnote.bootstrap;

import io.llmnote.audio.PodcastService;
import io.llmnote.gen.GenService;
import io.llmnote.note.Note;
import io.llmnote.note.NoteService;
import io.llmnote.notebook.Notebook;
import io.llmnote.notebook.NotebookRepository;
import io.llmnote.notebook.NotebookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等植入「内置系统笔记本」(owner_id=system):对所有用户只读可见、可问答。
 * 含两条笔记:① 项目架构 / 设计方案 / 使用说明;② AgentScope 生产级应用与组件机制介绍。
 * 正文写入后复用笔记摄取管线进 RAG,随后触发一次摘要文档 + 音频概览播客。
 * 已存在系统笔记本则整体跳过(不重复植入/生成)。摄取会调用 DashScope 向量化,放到后台线程避免阻塞启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemNotebookSeeder implements ApplicationRunner {

    private static final String OWNER = NotebookService.SYSTEM_OWNER;
    private static final String NB_NAME = "📌 系统公告 · 使用与原理";
    private static final String NB_DESC = "内置只读笔记本:项目架构与使用说明 + AgentScope 原理科普。可直接提问。";

    private final NotebookRepository notebookRepo;
    private final NoteService noteService;
    private final GenService genService;
    private final PodcastService podcastService;

    @Override
    public void run(ApplicationArguments args) {
        if (!notebookRepo.findByOwnerIdOrderByIdDesc(OWNER).isEmpty()) {
            log.info("system notebook already present, skip seeding");
            return;
        }
        Thread t = new Thread(this::seed, "system-notebook-seeder");
        t.setDaemon(true);
        t.start();
    }

    private void seed() {
        try {
            // 双重检查:并发/重启窗口下避免重复植入
            if (!notebookRepo.findByOwnerIdOrderByIdDesc(OWNER).isEmpty()) return;

            Notebook nb = new Notebook();
            nb.setName(NB_NAME);
            nb.setDescription(NB_DESC);
            nb.setOwnerId(OWNER);
            nb = notebookRepo.save(nb);
            Long nbId = nb.getId();
            log.info("seeding system notebook id={}", nbId);

            Note n1 = noteService.create(nbId, "一、项目架构 · 设计方案 · 使用说明", "RICHTEXT", OWNER);
            noteService.updateBody(nbId, n1.getId(), n1.getTitle(), NOTE1_HTML, OWNER);
            log.info("system note #1 ingested");

            Note n2 = noteService.create(nbId, "二、AgentScope 生产级应用与组件机制", "RICHTEXT", OWNER);
            noteService.updateBody(nbId, n2.getId(), n2.getTitle(), NOTE2_HTML, OWNER);
            log.info("system note #2 ingested");

            // 基于全部笔记生成摘要文档 + 音频概览(异步任务)
            genService.submit(nbId, "SUMMARY", null);
            podcastService.submit(nbId, null);
            log.info("system notebook seeded; summary + podcast generation triggered");
        } catch (Exception e) {
            log.error("seed system notebook failed", e);
        }
    }

    private static final String NOTE1_HTML = """
            <h2>鹿匠笔记 —— 项目总览</h2>
            <p>这是一个类 Google <b>NotebookLM</b> 的应用:上传自己的资料(PDF / Word / 文本 / 图片 / 音频),
            基于这些资料做<b>带出处引用的 RAG 问答</b>,并能一键生成<b>摘要 / 学习指南 / FAQ</b>,
            以及<b>音频概览播客</b>(两位 AI 主持人对话式讲解)。全部大模型能力统一走<b>通义千问 / DashScope</b>,
            微服务基础设施基于 <b>Spring Cloud + Nacos</b>,一键 Docker 部署。</p>

            <h3>三层内容模型(本项目特色)</h3>
            <p>内容组织为三层:<b>笔记本(Notebook)→ 笔记(Note)→ 笔记内容</b>。每条笔记内部同时容纳
            ① 富文本手写正文 与 ② 上传的文件。生成问答 / 文档 / 播客时,按前端<b>勾选的笔记</b>作为来源(默认全选);
            未勾选任何笔记时回退为整本笔记本。笔记正文本身被建模为一条 <code>NOTE_BODY</code> 类型的 source,
            从而复用统一的「解析→分块→向量化」摄取管线进入 RAG,并让引用出处能显示为笔记标题。</p>

            <h3>系统架构图</h3>
            <p>整体为<b>模块化单体 + 网关 + 注册中心</b>:浏览器请求经 Spring Cloud Gateway(<code>lb://</code> 服务发现路由)
            打到应用,应用内部按模块协作,大模型统一走 DashScope,元数据落 MySQL、向量落 Milvus。</p>
            <pre>
                          ┌─────────────┐
                          │   浏览器 SPA │  (原生 JS 单文件,应用直接托管)
                          └──────┬──────┘
                                 │ HTTP / SSE
                          ┌──────▼───────────────┐
                          │ Spring Cloud Gateway │  :9000  (lb:// 路由)
                          └──────┬───────────────┘
                                 │  服务发现
             注册/配置    ┌───────▼────────────────────────────────┐
          ┌──────────────┤        notebooklm-app  :8080           │
          │   Nacos      │  (模块化单体,JDK21 / Spring Boot 3.3) │
          │  :8848       ├────────────────────────────────────────┤
          └──────────────┤ auth │ notebook │ note │ ingest │ qa   │
                         │      │  gen │ audio │ log(访问日志)     │
                         └───┬──────────┬──────────┬───────────┬───┘
                             │          │          │           │
                    向量检索 │   元数据 │  大模型   │   缓存/会话 │
                       ┌─────▼───┐ ┌────▼────┐ ┌───▼──────┐ ┌──▼─────┐
                       │ Milvus  │ │ MySQL 8 │ │ DashScope │ │ Redis 7│
                       │(etcd+   │ │(notebook│ │ 通义千问   │ │        │
                       │ minio)  │ │/note/…) │ │qwen/embed │ │        │
                       └─────────┘ └─────────┘ │/asr/tts)  │ └────────┘
                                               └───────────┘
            </pre>
            <p><b>摄取链路</b>:上传文件 / 笔记正文 → 解析(TikaReader,图片走 <code>qwen-vl-max</code>、音频走 Paraformer)
            → 分块 → <code>text-embedding-v3</code> 向量化 → 写 Milvus(payload 带 notebook_id / note_id / source_id / seq)。
            <b>问答链路</b>:检索 Milvus(按所选笔记过滤)→ 拼上下文 → <code>qwen-plus</code> 生成 → SSE 流式返回并回填引用。</p>

            <h3>功能一览</h3>
            <ul>
              <li><b>资料摄取</b>:上传 PDF / Word / TXT / 图片 / 音频,自动解析、分块、向量化入库。</li>
              <li><b>图像理解</b>:图片经 <code>qwen-vl-max</code> 生成结构化描述后并入 RAG。</li>
              <li><b>音频转写</b>:音频经 Paraformer ASR 转写为文本后并入 RAG。</li>
              <li><b>RAG 问答</b>:基于所选笔记检索作答,SSE 流式返回,带可点击出处引用。</li>
              <li><b>文档生成</b>:一键生成摘要 / 学习指南 / FAQ(Markdown)。</li>
              <li><b>音频概览</b>:qwen 生成双主持人对话脚本 → CosyVoice 逐句合成 → 拼接为 MP3。</li>
            </ul>

            <h3>技术栈</h3>
            <table>
              <tr><th>层次</th><th>技术选型</th></tr>
              <tr><td>应用层</td><td>JDK 21、Spring Boot 3.3.5、Spring Cloud 2023.0.3 + Spring Cloud Alibaba(Nacos 配置 + 注册)、Spring Cloud Gateway(<code>lb://</code> 路由)、JPA / Hibernate</td></tr>
              <tr><td>智能体框架</td><td>AgentScope Java v2 <code>2.0.0-RC4</code>:<code>SimpleKnowledge</code> + <code>MilvusStore</code> + <code>TikaReader</code>(RAG 检索/解析)、<code>DashScopeChatModel</code>(对话/视觉)、<code>DashScopeTextEmbedding</code>(向量化)</td></tr>
              <tr><td>大模型 / SDK</td><td>DashScope SDK：文本对话 <code>qwen-plus</code>、图像理解 <code>qwen-vl-max</code>、文本向量 <code>text-embedding-v3</code>(1024 维)、语音识别 <code>paraformer-realtime-v2</code>、语音合成 <code>cosyvoice-v1</code>(双音色 longwan / longcheng)</td></tr>
              <tr><td>存储 / 基础设施</td><td>MySQL 8(元数据)、Redis 7(缓存/会话)、Milvus 2.x(向量库,伴生 etcd + minio)、Nacos 2.4.3(配置 + 注册中心)—— 全部 Docker</td></tr>
              <tr><td>鉴权 / 日志</td><td>自研 JWT(jjwt + BCrypt)+ 游客模式,<code>owner_id</code> 账号隔离;登录 / 操作访问日志(IP / 设备类型 / 动作)</td></tr>
              <tr><td>前端</td><td>单文件原生 JS SPA(<code>notebooklm-app/.../static/index.html</code>),应用直接托管,无独立构建步骤</td></tr>
            </table>

            <h3>模块与目录</h3>
            <p>后端按内部模块划分:<code>config</code>(AgentScope/DashScope/Milvus/CORS 配置)、<code>notebook</code>(笔记本&资料)、
            <code>note</code>(笔记层)、<code>ingest</code>(上传→解析→分块→向量化、图像理解、音频转写)、
            <code>qa</code>(RAG 问答 + 引用,SSE 流式)、<code>gen</code>(摘要/学习指南/FAQ)、<code>audio</code>(播客脚本 + TTS)、
            <code>auth</code>(自研 JWT + 游客模式,账号隔离)、<code>log</code>(登录 / 操作访问日志)。架构演进上当前为<b>模块化单体</b>,但从第一天即接入 Nacos,
            后续可按五个内部模块平滑拆成独立微服务,网关与注册中心已就位。</p>

            <h3>账号与权限</h3>
            <p>自研 JWT 鉴权(jjwt + BCrypt)+ 游客模式:每个主体以 <code>owner_id</code> 隔离数据(注册用户为 <code>u:&lt;id&gt;</code>,
            游客为 <code>g:&lt;uuid&gt;</code>)。本「系统公告」笔记本 <code>owner_id=system</code>,对所有人<b>只读可见、可问答</b>,
            但不可编辑 / 删除 / 上传;前端可一键隐藏此类系统笔记本。</p>

            <h3>部署与使用</h3>
            <ol>
              <li><b>配置</b>:<code>cp .env.example .env</code>,至少填入 <code>DASHSCOPE_API_KEY</code>。</li>
              <li><b>一键启动</b>:<code>set -a &amp;&amp; . ./.env &amp;&amp; set +a</code> 后 <code>docker compose up -d --build</code>;
                  基础设施(mysql/nacos/redis/milvus)healthcheck 就绪后 app 与 gateway 才启动。</li>
              <li><b>访问</b>:浏览器打开 <code>http://localhost:9000</code>(经网关)或 <code>http://localhost:8080</code>(直连应用)。</li>
            </ol>
            <p><b>界面操作</b>:左侧新建/选择笔记本 → 中栏笔记列表(带勾选,默认全选)→ 右侧四个标签:
            <b>笔记</b>(富文本正文 + 上传文件)、<b>问答</b>(流式作答 + 引用角标)、<b>生成</b>(摘要/学习指南/FAQ)、
            <b>播客</b>(音频概览,约 1–2 分钟)。资料摄取状态实时轮询:<code>PENDING → PARSING → EMBEDDING → DONE/FAILED</code>。</p>
            <p><b>常用运维</b>:<code>docker compose logs -f app</code> 跟踪日志;<code>docker compose up -d --build app</code> 仅重建应用;
            <code>docker compose down</code> 停止(保留数据卷)。MySQL 对外映射 <code>3307</code> 以避开本机 3306。</p>
            """;

    private static final String NOTE2_HTML = """
            <h2>AgentScope:可观测、可信赖的智能体框架</h2>
            <p><b>AgentScope</b> 是由阿里巴巴通义团队开源的多智能体(multi-agent)框架,口号是
            「Build and run agents you can see, understand and trust」——构建你能看得见、理解得了、信得过的智能体。
            它同时提供 <b>Python</b> 与 <b>Java</b> 两套实现,本项目使用的正是 <b>AgentScope Java v2</b>。
            相关论文:《AgentScope: A Flexible yet Robust Multi-Agent Platform》(arXiv:2402.14034)与
            《AgentScope 1.0: A Developer-Centric Framework for Building Agentic Applications》(arXiv:2508.16279)。</p>

            <h3>AgentScope 2.0 的设计取向</h3>
            <p>2.0 是一个<b>生产就绪(production-ready)、易用</b>的智能体框架,核心理念是<b>面向能力越来越强的 LLM 而设计</b>:
            充分发挥模型自身的推理与工具调用能力,而不是用严格的提示词和固化的编排去束缚它。框架提供一组随模型能力增长仍然适用的基础抽象。</p>

            <h3>核心组件与机制</h3>
            <ul>
              <li><b>Message(消息)</b>:智能体之间、以及与用户交互的统一信息载体(如 <code>UserMsg</code>),
                  内容由可组合的 block 构成(文本块、工具调用块等)。</li>
              <li><b>Agent / ReAct 智能体</b>:以「推理—行动(reasoning-acting)」循环驱动;
                  可配置 <code>system_prompt</code>、模型与工具集,支持流式回复(<code>reply_stream</code>)。</li>
              <li><b>Tool / Toolkit(工具)</b>:内置 Bash、Grep、Glob、Read、Write、Edit 等工具,
                  智能体通过工具调用与环境交互;工具集可扩展。</li>
              <li><b>Model / Credential</b>:通过 <code>DashScopeChatModel</code> 等对接模型服务(如通义千问),
                  凭证与模型配置解耦。</li>
              <li><b>Event System(事件系统)</b>:统一的事件总线,把智能体内部过程(回复开始、模型调用、
                  文本块增量/结束等 <code>EventType</code>)流式暴露给前端,天然支持 human-in-the-loop 与 UI 实时呈现。</li>
              <li><b>Permission System(权限系统)</b>:对工具与资源做细粒度、可配置的管控,并支持 bypass 模式
                  (端到端运行、不为每次工具调用停下确认)。</li>
              <li><b>Workspace / Sandbox(工作区/沙箱)</b>:在隔离环境中运行工具与代码,内置 local、Docker、E2B 等后端。</li>
              <li><b>Middleware(中间件)</b>:可组合的钩子,用于定制和扩展智能体的「推理—行动」循环。</li>
              <li><b>Memory(记忆)</b>:除会话短期记忆外,支持长期记忆与 Agentic Memory,并可集成 Mem0、ReMe 等方案。</li>
              <li><b>RAG(检索增强生成)</b>:官方支持文档知识库检索;本项目即用其 <code>SimpleKnowledge</code> +
                  向量库 + <code>TikaReader</code> 解析构建 RAG 管线。</li>
            </ul>

            <h3>生产级能力:Agent Service 与 Agent Team</h3>
            <p>AgentScope 提供一个基于 <b>FastAPI</b> 的可扩展 <b>Agent Service</b>,支持<b>多租户(multi-tenancy)</b>与
            <b>多会话(multi-session)</b>,并自带 Web UI,面向生产的隔离与服务化开箱即用。典型能力包括:</p>
            <ul>
              <li><b>Agent Team(智能体团队)</b>:一个 leader 智能体派生并协调多个 worker,通过内置团队工具协作完成任务。</li>
              <li><b>Task planning(任务规划)</b>:把复杂工作拆解为可追踪的计划并在执行中动态更新。</li>
              <li><b>Permission control · bypass 模式</b>:无人值守地端到端运行。</li>
              <li><b>Background task offloading(后台任务卸载)</b>:长耗时工具转入后台执行,完成后其结果再唤醒智能体、恢复对话。</li>
              <li><b>分布式 &amp; 多租户 &amp; 多会话 RAG 服务</b>:面向规模化部署的检索服务。</li>
            </ul>

            <h3>快速上手(Python 示例的心智模型)</h3>
            <p>以官方 “Hello AgentScope” 为例:创建一个 <code>Agent</code>(取名 Friday),给它一个 <code>system_prompt</code>、
            一个 <code>DashScopeChatModel</code>(凭证来自 <code>DASHSCOPE_API_KEY</code>)、以及一个含 Bash/Grep/Read/Write/Edit 的
            <code>Toolkit</code>;随后以 <code>UserMsg</code> 发起对话,通过 <code>reply_stream</code> 消费事件流,按
            <code>EventType</code>(如文本块增量 <code>TEXT_BLOCK_DELTA</code>)驱动 UI 更新。这正体现了「事件驱动 + 工具调用 + 流式呈现」的设计。</p>

            <h3>本项目如何使用 AgentScope</h3>
            <p>本 鹿匠笔记 使用 <b>AgentScope Java <code>2.0.0-RC4</code></b>:用 <code>SimpleKnowledge</code> 对接
            <code>MilvusStore</code> 做向量检索,用 <code>TikaReader</code> 解析并切分上传文档,用
            <code>DashScopeChatModel</code> 完成问答与文档生成,用 <code>DashScopeTextEmbedding</code> 做
            <code>text-embedding-v3</code> 向量化。检索层面在 payload 上带 <code>notebook_id</code> / <code>note_id</code> /
            <code>source_id</code> / <code>seq</code>,以支持按笔记本或所选笔记过滤,并做引用溯源。</p>

            <p class="mut"><i>参考来源:AgentScope 官方仓库 README 与文档(docs.agentscope.io)、arXiv 论文 2402.14034 与 2508.16279。
            以上为科普性质介绍,具体 API 以官方最新文档为准。</i></p>
            """;
}
