# Ultimate-AI-Media-Generator-Skill 提示词模板 → 项目映射

来源: `docs/prompt-references/ultimate-media-skill/`
本仓库**不安装、不运行**该 skill,仅借鉴其 prompt 工程思路。

## 借鉴价值从高到低

### 🟢 高价值:可以直接抄思路

#### 1. `curated-prompts.md` § 7 类目化 prompt 结构
该 skill 把 prompt 按用途分成 7 类(产品图、人物肖像、3D 吉祥物、建筑、故事板、社交缩略图、视频)。
**你的项目**:`XhsService` 写小红书图文、`ForumMediaService` 配论坛帖子封面。
- **借鉴点**:用「`用途 → 模板`」表把 prompt 拆类,而不是在代码里 inline 一长串。当前 `XhsService` 是单条硬编码,改成 5-6 个模板分派会更易维护。
- **可建文件**:`io.llmnote.studio.ImagePromptTemplates`,按 (用途 × 风格) 索引。

#### 2. `curated-prompts.md` § 1-6 的"modifier 拼接"思路
所有模板都是 `[主体] + [镜头/构图] + [光线] + [风格] + [画质修饰]` 五段式。
**你的项目**:论坛帖子配图 prompt 现在直接喂中文标题 + 内容,模型容易抓不住主体。
- **借鉴点**:`主体 = (post.title 关键词 + category 类型)`,然后挂上:
  - `daily` 类:`medium shot, soft natural light, slice-of-life photography`
  - `acrostic`:`macro shot, artistic, dreamy bokeh, watercolor feel`
  - `joke`:`3D cartoon style, bright pop colors, expressive pose`
  - `video_series`:`cinematic, 16:9 wide shot, dramatic lighting, film grain`
- **代码改动**:`ForumMediaService` 在调 `cogview` 前,挂一个 `applyStyleModifier(category, rawPrompt)`。

#### 3. `ai-comic-drama-skill.md` 的"角色一致性 checklist"
它的 4 步输出包含「**角色一致性 checklist**」和「**style lock instructions**」。
**你的项目**:AI 居民发帖时,如果配图里要画某个 agent(比如"张作家"在咖啡馆),现在每次 prompt 都不一样,模型每次画出的"张作家"脸都不一样。
- **借鉴点**:在 `WorldMediaRunner` 里给每个 `AgentEmployee` 维护一个 `visualDescriptor` 字段(用 LLM 一次性生成,后续所有帖子配图都拼上)。`forum.attachments` 多张图时,所有图共享同一段角色描述,提升一致性。

### 🟡 中等价值:思路可参考,实现复杂

#### 4. `ai-ppt-skill.md` 的"先做 plan,再批量出图"流水线
输入 4 个变量(topic/audience/count/style),输出 5 类产物(hero + 每页 + icon + palette + alt)。
**你的项目**:小红书图文流是分步串行 — 写文案 → 文生图 → 配乐,没法一次出 6 张图。
- **借鉴点**:`XhsService` 可以加一个 "asset plan" 阶段:LLM 一次返回 6 个 image prompt 数组(封面 + 5 张内文图),再并发调 `cogview` 出图。当前是 1 prompt 1 图片。

#### 5. `ai-seo-article-skill.md` 的"1:1 + 16:9 比例分工"
社交预览用 1:1,文章 banner 用 16:9。
**你的项目**:论坛帖子配图目前固定 1:1 方形,没考虑过 XHS/微博这类长方形封面场景。
- **借鉴点**:`XhsService` 已经在用竖版 3:4,但**论坛帖子配图**应该按 social 平台区分。`ForumMediaService` 可以加一个 `aspectRatio` 字段(由 category 决定,`video_series=16:9`, `joke=1:1`, `acrostic=3:4`)。

### 🔴 低价值:别学

- CyberBara API 客户端(整份 `SKILL.md` 在讲它自家 API 怎么调)—— 你走的是阿里/智谱直连,完全无关。
- `quote credits` 流程 —— 你不靠 credits 计费,直接按调用计费。

## 落地建议(按工作量排序)

| 任务 | 工作量 | 收益 |
|---|---|---|
| **A. `ForumMediaService` 加 category-specific 风格修饰器** | 2 小时 | 论坛配图质量肉眼可见提升,6 类目各自有味道 |
| **B. `XhsService` 加 asset plan 阶段,并发生图** | 半天 | XHS 单篇产出时间砍半,封面+5 内文并发 |
| **C. `AgentEmployee` 加 `visualDescriptor` 字段** | 1 天 | 角色一致性 +50%(需要 schema 迁移 + prompt 模板化) |

建议先做 **A**,立竿见影;再考虑 B;**C 等论坛用户多了再做**(前期发帖量小,一致性不明显)。
