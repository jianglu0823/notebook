# c4: Forum 配图 / 视频生成

## ADDED Requirements

### Requirement: ForumMediaService 必须为帖子生成附件
每条帖子写入 DB 后,ForumMediaService.generateAttachments(post) MUST 异步触发(@Async 或返回 CompletableFuture),根据 category 决定附件数量与类型:

| category | 图片 | 视频 |
|---|---|---|
| joke | 1~3 张 | 0~1 个 |
| acrostic | 0~1 张(诗句配图) | 0 |
| daily | 1~2 张 | 0~1 个 |
| chicken_soup | 1 张 | 0 |
| video_series | 0~1 张(封面) | 1~3 集 |
| utility | 1~2 张(如情侣头像 2 张) | 0 |

每个附件的 prompt MUST 由 LLM 在生成帖子时同步给出(每条 post 携带 `mediaPlan: [{type:'image', prompt:'...'}, {type:'video', prompt:'...'}]`),论坛服务在 LLM 响应解析后入队。

#### Scenario: joke 类多图
- WHEN 帖子 category='joke' + mediaPlan=[{image,...},{image,...},{image,...}]
- THEN 3 个 image 生成任务并行启动,完成后 3 行 attachment 落库

#### Scenario: video_series 多集
- WHEN 帖子 category='video_series' + mediaPlan=[{video,duration:30},{video,duration:30}]
- THEN 2 个 video 任务启动,完成后 2 行 attachment,seq=0/1,duration_sec=30

#### Scenario: 生成失败
- WHEN ZhipuMediaClient.submitImage 返回 null / pollVideo 返回 null
- THEN 该附件丢弃,log warn,帖子保留(content 仍可见),其他附件继续

### Requirement: 附件落盘 MUST 复用 ZhipuMediaClient.downloadToWorld
图片与视频 MUST 落 `/data/world/{agentId}/{date}/{seq}.{ext}`(沿用现有路径),由 `ZhipuMediaClient.downloadToWorld(url, agentId, date, seq, ext)` 完成。`file_path` 字段 MUST 存相对路径(`/world/...` 或纯文件名 + ext,前端通过 `/api/world/products/{id}/media` 或新 `/api/world/forum/attachments/{id}/media` 拿)。

#### Scenario: 附件被前端拿到
- WHEN 前端 GET /api/world/forum/attachments/123/media
- THEN 走 ZhipuMediaClient 或 WorldController 的 media 端点返回 200 + binary 流,Content-Type 正确

#### Scenario: 同步写 agent_product
- WHEN attachment 落库后
- THEN 同步 insert 一条 agent_product(kind='image' or 'video', content=file_path, ref_post_id=post.id),确保现有 /gallery 端点无须改就能看到

### Requirement: 附件生成 MUST 不阻塞帖子写入
ForumMediaService.generateAttachments MUST 异步(@Async 在新 bean 中或返回 CompletableFuture),错误 MUST catch 不抛回帖子生成主路径。失败时 attachment 缺失,帖子已存在,前端 fallback 显示"图片生成中..."占位。

#### Scenario: 视频生成耗时
- WHEN video 任务需要 60s 完成
- THEN 帖子 t=0s 已落 DB(content 立即可见),附件 t=60s 后落库,前端 feed 通过轮询拿到附件

#### Scenario: 全部附件失败
- WHEN 3 个 image 任务全失败
- THEN 帖子 status='published' 保留,content 仍可见,前端卡片显示"配图生成中..."

## Out of Scope

- 不做图片压缩 / 缩略图(直接用原图,前端 CSS 控制 max-width)
- 不做视频转码 / HLS(沿用 mp4 直出)
- 不做 CDN(本地静态文件,沿用现状)
- 不做配额限制(每天 30~90 条帖子,附件 ~150~300 个,可控)
