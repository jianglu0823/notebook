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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // 始终后台跑一次同步:不存在则植入,已存在则按内容 diff 增量更新(见 sync())。
        Thread t = new Thread(this::sync, "system-notebook-seeder");
        t.setDaemon(true);
        t.start();
    }

    /** 一条内置笔记的定义:标题 + 正文 HTML。标题作为幂等键,用于 upsert / 清理废弃笔记。 */
    private record SeedNote(String title, String html) {}

    private List<SeedNote> notes() {
        return List.of(
                new SeedNote("一、项目架构 · 设计方案 · 使用说明", NOTE1_HTML),
                new SeedNote("二、AgentScope 生产级应用与组件机制", NOTE2_HTML),
                new SeedNote("三、新闻聚合 · 小红书文案工作台", NOTE3_HTML),
                new SeedNote("四、多智能体协作写作(作者 / 核查 / 主编)", NOTE4_HTML),
                new SeedNote("五、智能体小世界「鹿匠小镇」", NOTE5_HTML));
    }

    /**
     * 幂等同步内置系统笔记本:
     * <ul>
     *   <li>笔记本不存在 → 新建并植入全部笔记,再触发一次摘要 + 播客生成。</li>
     *   <li>笔记本已存在 → 按标题 upsert 每条笔记:内容有变才重写(省去无谓的重摄取),
     *       缺失的补建,标题已从定义中移除的旧笔记删除。有任何改动才重新生成摘要 + 播客。</li>
     * </ul>
     */
    private void sync() {
        try {
            List<Notebook> existing = notebookRepo.findByOwnerIdOrderByIdDesc(OWNER);
            boolean fresh = existing.isEmpty();
            Notebook nb;
            if (fresh) {
                nb = new Notebook();
                nb.setOwnerId(OWNER);
                nb.setName(NB_NAME);
                nb.setDescription(NB_DESC);
                nb = notebookRepo.save(nb);
                log.info("seeding system notebook id={}", nb.getId());
            } else {
                nb = existing.get(0);
                // 名称/描述若有更新一并同步
                if (!NB_NAME.equals(nb.getName()) || !NB_DESC.equals(nb.getDescription())) {
                    nb.setName(NB_NAME);
                    nb.setDescription(NB_DESC);
                    nb = notebookRepo.save(nb);
                }
            }
            Long nbId = nb.getId();

            List<SeedNote> defs = notes();
            Set<String> wanted = new HashSet<>();
            for (SeedNote sn : defs) wanted.add(sn.title());

            List<Note> current = noteService.list(nbId, OWNER);
            boolean changed = fresh;

            // 删除已从定义中移除的旧笔记(标题为幂等键)
            for (Note ex : current) {
                if (!wanted.contains(ex.getTitle())) {
                    noteService.delete(nbId, ex.getId(), OWNER);
                    log.info("system note removed: {}", ex.getTitle());
                    changed = true;
                }
            }

            // upsert:标题命中则内容 diff,未命中则新建
            for (SeedNote sn : defs) {
                Note match = current.stream()
                        .filter(n -> sn.title().equals(n.getTitle())).findFirst().orElse(null);
                if (match == null) {
                    Note created = noteService.create(nbId, sn.title(), "RICHTEXT", OWNER);
                    noteService.updateBody(nbId, created.getId(), sn.title(), sn.html(), OWNER);
                    log.info("system note created: {}", sn.title());
                    changed = true;
                } else if (!sn.html().equals(match.getContent())) {
                    noteService.updateBody(nbId, match.getId(), sn.title(), sn.html(), OWNER);
                    log.info("system note updated: {}", sn.title());
                    changed = true;
                }
            }

            if (changed) {
                genService.submit(nbId, "SUMMARY", null);
                podcastService.submit(nbId, null);
                log.info("system notebook synced (fresh={}); summary + podcast regenerated", fresh);
            } else {
                log.info("system notebook already up to date, no changes");
            }
        } catch (Exception e) {
            log.error("sync system notebook failed", e);
        }
    }

    private static final String NOTE1_HTML = """
            <h2>鹿匠笔记 —— 项目总览</h2>
            <p>这是一个类 Google <b>NotebookLM</b> 的应用:上传自己的资料(PDF / Word / 文本 / 图片 / 音频),
            基于这些资料做<b>带出处引用的 RAG 问答</b>,并能一键生成<b>摘要 / 学习指南 / FAQ</b>,
            以及<b>音频概览播客</b>(两位 AI 主持人对话式讲解)。在此之上还长出了<b>创作工作台</b>
            (新闻聚合 / 小红书文案 / 多智能体协作写作)与<b>智能体小世界「鹿匠小镇」</b>
            (会自我生活、创作、繁衍的 AI 居民)。大模型能力<b>默认走智谱 GLM 免费模型</b>
            (<code>glm-4.7-flash</code> 文本、<code>glm-z1-flash</code> 推理、<code>cogview-3-flash</code> 文生图、
            <code>cogvideox-flash</code> 文生视频),并可切换到<b>通义千问 / DashScope</b> 付费模型;
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
              <li><b>新闻聚合</b>:选方向 → qwen <code>enable_search</code> 联网检索最新动态 → 整理成笔记入库。</li>
              <li><b>小红书工作台</b>:方向→标题→联网素材→风格文案→通义万相配图的多步创作流水线。</li>
              <li><b>多智能体协作写作</b>:作者 / 核查员 / 主编三个 ReActAgent 迭代收敛,核查员自主联网核实事实,过程实时围观。</li>
              <li><b>智能体小世界「鹿匠小镇」</b>:一批有人设的 AI 居民自主生活——每日结算(工资/学习/创作)、开会、1:1 对话、恋爱婚育、老去离世(含一生回顾悼词),还能用「沙盒快进」在隔离环境里模拟数十年演化。</li>
            </ul>

            <h3>技术栈</h3>
            <table>
              <tr><th>层次</th><th>技术选型</th></tr>
              <tr><td>应用层</td><td>JDK 21、Spring Boot 3.3.5、Spring Cloud 2023.0.3 + Spring Cloud Alibaba(Nacos 配置 + 注册)、Spring Cloud Gateway(<code>lb://</code> 路由)、JPA / Hibernate</td></tr>
              <tr><td>智能体框架</td><td>AgentScope Java v2 <code>2.0.0-RC4</code>:<code>SimpleKnowledge</code> + <code>MilvusStore</code> + <code>TikaReader</code>(RAG 检索/解析)、<code>DashScopeChatModel</code>(对话/视觉)、<code>DashScopeTextEmbedding</code>(向量化)</td></tr>
              <tr><td>大模型 / SDK</td><td><b>默认智谱 GLM 免费模型</b>(OpenAI 兼容):文本 <code>glm-4.7-flash</code>、推理 <code>glm-z1-flash</code>、文生图 <code>cogview-3-flash</code>、文生视频 <code>cogvideox-flash</code>、多模态 <code>glm-4.6v-flash</code>,主模型失败按序降级备用免费模型;<b>可选 DashScope 付费</b>:对话 <code>qwen-plus</code>/<code>qwen-flash</code>、图像理解 <code>qwen-vl-max</code>、文本向量 <code>text-embedding-v3</code>(1024 维)、语音识别 <code>paraformer-realtime-v2</code>、语音合成 <code>cosyvoice-v1</code>(双音色 longwan / longcheng)</td></tr>
              <tr><td>存储 / 基础设施</td><td>MySQL 8(元数据)、Redis 7(缓存/会话)、Milvus 2.x(向量库,伴生 etcd + minio)、Nacos 2.4.3(配置 + 注册中心)—— 全部 Docker</td></tr>
              <tr><td>鉴权 / 日志</td><td>自研 JWT(jjwt + BCrypt)+ 游客模式,<code>owner_id</code> 账号隔离;登录 / 操作访问日志(IP / 设备类型 / 动作)</td></tr>
              <tr><td>前端</td><td>单文件原生 JS SPA(<code>notebooklm-app/.../static/index.html</code>),应用直接托管,无独立构建步骤</td></tr>
            </table>

            <h3>模块与目录</h3>
            <p>后端按内部模块划分:<code>config</code>(AgentScope/DashScope/Milvus/CORS 配置)、<code>notebook</code>(笔记本&资料)、
            <code>note</code>(笔记层)、<code>ingest</code>(上传→解析→分块→向量化、图像理解、音频转写)、
            <code>qa</code>(RAG 问答 + 引用,SSE 流式)、<code>gen</code>(摘要/学习指南/FAQ)、<code>audio</code>(播客脚本 + TTS)、
            <code>news</code>(新闻方向联网聚合)、<code>studio</code>(创作工作台:小红书文案流水线 + 多智能体协作写作)、
            <code>world</code>(智能体小世界:居民/每日结算/会议/沙盒快进/关系经济/创作产物)、
            <code>llm</code>(多后端模型工厂:智谱 GLM 免费 + DashScope 付费,含降级重试与 token→元 费用换算)、
            <code>auth</code>(自研 JWT + 游客模式,账号隔离)、<code>log</code>(登录 / 操作访问日志)。架构演进上当前为<b>模块化单体</b>,但从第一天即接入 Nacos,
            后续可按内部模块平滑拆成独立微服务,网关与注册中心已就位。</p>

            <h3>账号与权限</h3>
            <p>自研 JWT 鉴权(jjwt + BCrypt)+ 游客模式:每个主体以 <code>owner_id</code> 隔离数据(注册用户为 <code>u:&lt;id&gt;</code>,
            游客为 <code>g:&lt;uuid&gt;</code>)。本「系统公告」笔记本 <code>owner_id=system</code>,对所有人<b>只读可见、可问答</b>,
            但不可编辑 / 删除 / 上传;前端可一键隐藏此类系统笔记本。</p>

            <h3>部署与使用</h3>
            <ol>
              <li><b>配置</b>:<code>cp .env.example .env</code>,至少填入 <code>ZHIPU_API_KEY</code>(默认免费模型);
                  若要用通义千问付费模型 / 向量 / 语音,再填 <code>DASHSCOPE_API_KEY</code>。</li>
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

    private static final String NOTE3_HTML = """
            <h2>创作工作台:从「读资料」到「产内容」</h2>
            <p>除了围绕自有资料做 RAG 问答,鹿匠笔记还内置一个<b>内容创作工作台</b>,把大模型的<b>联网检索</b>与
            <b>文生图</b>能力串成可交付的生产流水线。目前包含两条:<b>新闻方向聚合</b>与<b>小红书文案生成</b>。</p>

            <h3>一、新闻聚合</h3>
            <p>选一个预设方向(或自定义关键词)→ 后端用 qwen 的 <code>enable_search</code> 联网检索该方向的最新动态 →
            由大模型整理成一篇结构化笔记,直接落进指定笔记本成为一条 <code>Note</code>,随后即可复用 RAG 问答 / 生成 / 播客。
            实现上遵循统一的<b>异步任务 + 前端轮询</b>范式:提交建 <code>PENDING</code> 任务 →
            <code>@Async</code> 联网整理 → <code>DONE/FAILED</code>,前端 <code>GET /{id}</code> 轮询进度。</p>

            <h3>二、小红书文案工作台(多步向导)</h3>
            <p>一条 <code>xhs_project</code> 记录贯穿全流程,状态机推进:
            <code>NEW → TITLES_DONE → RESEARCH_DONE → COPY_DONE → IMAGES_DONE</code>,每一步都是独立异步任务:</p>
            <ol>
              <li><b>方向 → 标题</b>:输入方向(如「秋季通勤穿搭」),联网扩写出 6~8 个候选<b>标题方向</b>。</li>
              <li><b>标题 → 素材</b>:选定标题后,联网检索该标题的全量素材,汇成一篇长文。</li>
              <li><b>风格 → 文案</b>:选风格(种草 / 毒舌 / 干货 / 治愈),按风格化 system prompt 生成小红书文案
                  (标题党标题 + 正文 + emoji + #话题标签),支持一键复制。</li>
              <li><b>配图</b>:先让 qwen 把文案压成画面描述 prompt,再用<b>通义万相 <code>wanx</code></b>
                  (<code>ImageSynthesis</code>)逐条文生图,下载 OSS 临时链接<b>落盘</b>持久化后由应用吐 PNG。</li>
              <li><b>发布管理</b>:本地草稿 / 待发 / 已发状态流转 + 一键复制文案 + 下载配图
                  (小红书无公开发布 API,不做直发)。</li>
            </ol>
            <p>后端在 <code>io.llmnote.studio</code> 包,复用 <code>DashScopeChatModel</code> / <code>ChatCompletion</code> /
            <code>enable_search</code> 联网检索 / <code>ImageSynthesis</code> 文生图;控制器 <code>/api/studio/xhs</code>
            按 <code>owner_id</code> 做账号隔离。</p>
            """;

    private static final String NOTE4_HTML = """
            <h2>多智能体协作写作:让三个 Agent 互相「较真」</h2>
            <p>这是本项目最能体现 <b>agentic(自主智能体)</b> 特性的场景:三个 <code>ReActAgent</code> 被编排成一个
            <b>迭代收敛循环</b>,围绕一个写作主题分工协作、反复打磨,直到主编满意或用尽轮数。与小红书那条<b>硬编码顺序状态机</b>
            本质不同——这里核查员<b>由 LLM 自主决定</b>是否 / 何时 / 用什么关键词联网核实,是真正的「推理—行动」循环。</p>

            <h3>三个角色</h3>
            <ul>
              <li><b>✒️ 作者(author)</b>:根据主题与反馈撰写 / 打磨稿件,只输出正文。</li>
              <li><b>🔎 核查员(factchecker)</b>:挂载 <code>web_search</code> 工具,在 ReAct 循环中<b>自主联网</b>逐条核实
                  数据 / 时间 / 人物 / 事件 / 专有名词,严禁凭记忆下结论,给出核实结论与修正建议。</li>
              <li><b>🧑‍⚖️ 主编(editor)</b>:从结构 / 表达 / 吸引力 / 切题四方面审稿,并结合核查报告做出裁决,
                  末尾输出 <code>VERDICT: APPROVE</code> 或 <code>VERDICT: REVISE</code>(收敛信号)。</li>
            </ul>

            <h3>协作流程</h3>
            <p>作者写初稿 → 每轮:核查员联网核实 + 主编审稿裁决 → 若 <code>APPROVE</code> 则收敛定稿;否则作者据反馈改稿,
            最多 <code>maxRounds</code>(1~5)轮。每一步的思考 / 工具调用<b>实时写入事件时间线</b>,前端轮询即可
            「围观」智能体协作过程——包括核查员正在<b>搜什么关键词</b>、检索到什么事实预览,把原本只在后端日志里的
            「思考」暴露到 UI。</p>

            <h3>技术要点</h3>
            <ul>
              <li><b>ReAct 智能体</b>:<code>ReActAgent.builder().name().sysPrompt().model(chatModel).toolkit(..).maxIters(..)</code>,
                  <code>agent.call(prompt, RuntimeContext.empty()).block()</code> 阻塞取回复(无状态、可多用户并发)。</li>
              <li><b>Agentic 工具调用</b>:<code>Toolkit</code> 注册带 <code>@Tool</code> 注解的 <code>WebSearchTool</code>,
                  底层仍走 qwen <code>enable_search</code> 返回 grounded 事实;每次调用回调 listener 把查询写入事件流。</li>
              <li><b>持久化</b>:<code>writing_project</code> 记录 <code>rounds</code>(每轮 draft/factcheck/review/verdict)与
                  <code>events</code>(事件时间线)两个 JSON,前端逐段轮询渲染。</li>
            </ul>
            <p>后端在 <code>io.llmnote.studio</code> 包(<code>WritingAgentService</code> / <code>WebSearchTool</code>),
            控制器 <code>/api/studio/writing</code> 按 <code>owner_id</code> 做账号隔离。</p>
            """;

    private static final String NOTE5_HTML = """
            <h2>智能体小世界「鹿匠小镇」:会自己生活的 AI 居民</h2>
            <p>这是本项目最「活」的模块:一批有<b>人设、职业、心情、金币、精力</b>的 AI 居民住在同一个小镇里,
            自主上班、学习、创作、开会、社交、恋爱结婚、生儿育女,也会老去和离世。你既可以只当「围观者」看小镇
            一天天演化,也可以亲自<b>捏一位居民</b>、和 TA 一对一聊天,或用<b>沙盒快进</b>在隔离环境里推演几十年后的结局。
            后端在 <code>io.llmnote.world</code> 包,大模型默认走<b>智谱 GLM 免费模型</b>,几乎零成本地把小镇跑起来。</p>

            <h3>一、居民与「对话捏人」</h3>
            <p>每位居民(<code>AgentEmployee</code>)有名字、头像 emoji、主题色、身份/职业、<b>性格人设</b>(作为其系统提示)、
            心情、生日,以及一套<b>创作技能</b>。新增居民有两种方式:表单直填,或用<b>「对话捏人」</b>——像聊天一样描述你想要的
            居民,AI(用千问 <code>qwen-flash</code>,便宜快)追问缺失细节,信息足够时给出人设草案与技能建议,确认后一键落库。</p>

            <h3>二、创作技能系统(novel / image / video / music)</h3>
            <p>每位居民可拥有 0~4 项技能,以 JSON 记录 <code>{"novel":{"lv":3,"style":"武侠向"}, ...}</code>:
            <b>lv</b> 为熟练度(1~10),<b>style</b> 为该技能专属的风格提示词。四类技能对应四种产物:</p>
            <ul>
              <li><b>✍️ novel(写小说)</b> → 连载<b>章节</b>(文本)</li>
              <li><b>🎨 image(画图片)</b> → <b>画作</b>(智谱 <code>cogview-3-flash</code> 文生图)</li>
              <li><b>🎬 video(拍短片)</b> → <b>短片</b>(智谱 <code>cogvideox-flash</code> 文生视频,异步生成)</li>
              <li><b>🎤 music(写歌词)</b> → <b>歌词</b>(文本;智谱暂无音频生成 API,故产出歌词而非音频)</li>
            </ul>
            <p>居民会<b>每日自学</b>:有概率给已有技能升级、或习得一项新技能(最多 4 项);技能等级越高,当天触发对应
            <b>创作事件</b>的概率越大、产物越多。新增居民时可勾选技能并各填一句风格提示词,决定 TA 擅长创作的方向。</p>

            <h3>三、每日结算(小镇的心跳)</h3>
            <p>小镇按「天」推进,每次结算对所有在册居民依次:<b>发放工资</b>(按职业给稿费 / 演出 / 卖画 / 创作收入)→
            <b>技能自学</b> → 按技能概率<b>触发创作</b>并落 <code>AgentProduct</code>(章节/画作/短片/歌词)→
            汇总当天亮点(谁写了什么、谁画了什么)。可开启<b>自主行动</b>让小镇按设定间隔自动往前跑,前端实时看到事件涌现。</p>

            <h3>四、社交 · 关系 · 婚育 · 生死</h3>
            <p>居民之间会产生<b>关系</b>与<b>经济</b>往来,可发起<b>多人会议</b>(<code>MeetingRunner</code> 异步编排)或
            <b>1:1 对话</b>。随时间推移会<b>恋爱、结婚、生子</b>(新生儿继承姓氏),也会因年龄 / 精力而<b>老去、离世</b>——
            离世时由推理模型(<code>glm-z1-flash</code>)生成一段<b>一生回顾悼词</b>,进入「小黑屋」永久纪念。</p>

            <h3>五、沙盒快进(隔离推演几十年)</h3>
            <p>选定若干居民作为种子,指定推演<b>年数</b>(1~50),<b>沙盒快进</b>会在一个<b>完全隔离的纯规则引擎</b>里
            (以居民当前属性的<b>内存快照</b>为起点,<b>绝不触碰真实世界任何表</b>)按年推进关系 / 婚育 / 死亡 / 经济 /
            产物 / 突发事件,逐条落 <code>SandboxEvent</code> 供前端「一年年」流式回放;仅在最后调用一次 LLM 写一段
            <b>世界报告</b>(省 token)。任务异步执行(<code>SandboxRunner</code> @Async),状态机
            <code>PENDING → RUNNING → DONE/FAILED</code>,前端轮询进度。服务重启会自动回收中断的孤儿任务置为 FAILED,
            避免永远卡在「推演中」。</p>

            <h3>技术要点</h3>
            <ul>
              <li><b>成本可控</b>:小镇默认全程走智谱 GLM 免费模型;<code>ChatModelFactory</code> 提供多模型工厂 +
                  限流退避重试 + 失败降级链 + token→元 费用换算。</li>
              <li><b>@Async 独立 bean</b>:结算 / 会议 / 沙盒 / 媒体生成等长耗时编排都拆成独立 bean 让 <code>@Async</code>
                  生效(同类自调用会退化为同步),不阻塞发起请求的 HTTP 线程。</li>
              <li><b>数据模型</b>:<code>agent_employee</code>(居民,含 <code>skills_json</code> 技能)、
                  <code>agent_product</code>(创作产物)、<code>agent_comment</code>(评论互动)、
                  <code>sandbox_run</code> / <code>sandbox_event</code>(沙盒任务与时间线)。</li>
              <li><b>账号隔离与归属</b>:居民 / 会议 / 沙盒任务均按 <code>owner_id</code> 隔离,管理员可见全部。</li>
            </ul>
            <p>后端在 <code>io.llmnote.world</code> 包(<code>WorldSimEngine</code> / <code>WorldController</code> /
            <code>AgentEmployeeService</code> / <code>SandboxRunner</code> / <code>MeetingRunner</code> /
            <code>WorldMediaRunner</code> / <code>AgentBuilderController</code> 等),媒体产物走 <code>ZhipuMediaClient</code>。</p>
            """;
}
