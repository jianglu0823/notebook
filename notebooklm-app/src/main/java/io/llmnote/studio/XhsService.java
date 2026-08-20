package io.llmnote.studio;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.llm.ChatCompletion;
import io.llmnote.llm.ZhipuMediaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 小红书文案生成工作流服务。四个异步阶段各自更新 xhs_project.status,前端逐段轮询:
 * 1) genTitles:qwen 联网搜索 + 扩写,给出候选标题(JSON 数组)→ TITLES_DONE
 * 2) research:针对选定标题联网搜集素材长文 → RESEARCH_DONE
 * 3) writeCopy:按风格生成小红书文案 → COPY_DONE
 * 4) genImages:文案 → 画面 prompt → 通义万相 wanx 文生图 → 下载落盘 → IMAGES_DONE
 * 数据隔离:所有操作先校验 project.ownerId == 调用方 ownerId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XhsService {

    private static final int TITLE_COUNT = 8;
    private static final int MAX_IMAGES = 3;
    private static final String IMG_SIZE = "1024*1024";
    /** 视频镜头段数上限(每段约 5s)。 */
    private static final int MAX_SCENES = 4;
    private static final int MIN_SCENES = 2;
    /** 短剧固定分镜段数(每段约 5s ≈ 30s 成片)。 */
    private static final int DRAMA_SCENES = 6;
    /** 短剧每段视频「等待重试到成功」的最大尝试次数(遇 429/超时会等待后重试)。 */
    private static final int DRAMA_SEG_MAX_ATTEMPTS = 8;
    /** 短剧分段重试的基础等待(ms),按尝试次数线性递增以躲免费额度限流。 */
    private static final long DRAMA_SEG_RETRY_BASE_MS = 15000;
    /** 竖屏 9:16。 */
    private static final String VIDEO_SIZE = "1080x1920";
    private static final SpeechSynthesisAudioFormat TTS_FMT = SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS;

    private final DashScopeChatModel chatModel;
    private final ChatCompletion chat;
    private final NotebookLmProperties props;
    private final ObjectMapper objectMapper;
    private final XhsProjectRepository repo;
    private final XhsDramaDraftRepository draftRepo;
    private final ZhipuMediaClient mediaClient;
    /** 自身代理:用于触发本类的 @Async 方法(同类自调用会绕过代理退化成同步,故经此拿到代理 bean)。 */
    private final ObjectProvider<XhsService> selfProvider;

    // ---- 提交入口 ----

    public XhsProject create(String ownerId, String direction) {
        XhsProject p = new XhsProject();
        p.setOwnerId(ownerId);
        p.setDirection(direction == null || direction.isBlank() ? "综合" : direction.trim());
        p.setStatus("NEW");
        p = repo.save(p);
        genTitlesAsync(p.getId());
        return p;
    }

    public XhsProject research(Long id, String ownerId, String chosenTitle) {
        XhsProject p = owned(id, ownerId);
        p.setChosenTitle(chosenTitle == null ? "" : chosenTitle.trim());
        p.setStatus("TITLES_DONE");
        repo.save(p);
        researchAsync(id);
        return p;
    }

    public XhsProject writeCopy(Long id, String ownerId, String style) {
        XhsProject p = owned(id, ownerId);
        p.setStyle(normalizeStyle(style));
        p.setStatus("RESEARCH_DONE");
        repo.save(p);
        writeCopyAsync(id);
        return p;
    }

    public XhsProject genImages(Long id, String ownerId) {
        XhsProject p = owned(id, ownerId);
        p.setStatus("COPY_DONE");
        repo.save(p);
        genImagesAsync(id);
        return p;
    }

    public XhsProject genVideo(Long id, String ownerId) {
        XhsProject p = owned(id, ownerId);
        p.setStatus("IMAGES_DONE");
        p.setVideoStatus("PENDING");
        p.setErrorMsg(null);
        repo.save(p);
        genVideoAsync(id);
        return p;
    }

    public XhsProject genDrama(Long id, String ownerId) {
        XhsProject p = owned(id, ownerId);
        p.setDramaStatus("PENDING");
        p.setDramaPath(null);
        p.setDramaSegmentsDone(0);
        p.setErrorMsg(null);
        repo.save(p);
        selfProvider.getObject().genDramaAsync(id);
        return p;
    }

    /**
     * 短剧独立入口:用主题(+ 4 个可选结构化输入)创建项目,清掉旧 drafts,异步生成 1 版候选脚本。
     * 4 个可选输入对应 imported prompt {@code ai-comic-drama-skill.md} 的 genre/character/arc/tone,
     * 留空时 LLM 根据 theme 自行补一个合理设定。
     * 生成成功会自动选为 chosenDraftId;失败用户可手动补全后再渲染。
     */
    public XhsProject createDrama(String ownerId, String theme, String genre, String mainCharacter, String storyArc, String tone) {
        String t = (theme == null || theme.isBlank()) ? "一个温暖的小故事" : theme.trim();
        XhsProject p = new XhsProject();
        p.setOwnerId(ownerId);
        p.setDirection(t.length() > 255 ? t.substring(0, 255) : t);
        p.setChosenTitle(t.length() > 512 ? t.substring(0, 512) : t);
        p.setStatus("DRAMA");
        p.setDramaStatus("PENDING");
        p.setErrorMsg(null);
        p.setGenre(emptyToNull(genre));
        p.setMainCharacter(emptyToNull(mainCharacter));
        p.setStoryArc(emptyToNull(storyArc));
        p.setTone(emptyToNull(tone));
        p.setChosenDraftId(null);
        p = repo.save(p);
        // 清掉旧 drafts(retry 场景),然后异步生成 1 版
        draftRepo.deleteByProjectId(p.getId());
        selfProvider.getObject().genDraftsAsync(p.getId());
        return p;
    }

    /**
     * 对已存在的短剧项目重跑脚本生成:保留主题 + 4 个输入,清掉旧 draft,状态置 PENDING 重新跑。
     * 用于 FAILED 后的快速 retry(不必走 newDrama 重提表单)。
     */
    public XhsProject regenerateDrama(Long id, String ownerId) {
        XhsProject p = owned(id, ownerId);
        if ("RENDERING".equals(p.getDramaStatus())) {
            throw new IllegalStateException("正在渲染中,不能重新生成");
        }
        draftRepo.deleteByProjectId(p.getId());
        p.setDramaStatus("PENDING");
        p.setErrorMsg(null);
        p.setChosenDraftId(null);
        p.setDramaPath(null);
        p.setDramaSegmentsDone(0);
        p = repo.save(p);
        selfProvider.getObject().genDraftsAsync(p.getId());
        return p;
    }

    /** 短剧独立工作台:列出该主体的全部短剧项目(最新在前)。 */
    public List<XhsProject> listDrama(String ownerId) {
        return repo.findByOwnerIdAndDramaStatusIsNotNullOrderByIdDesc(ownerId);
    }

    /** 列出该短剧项目的 3 版候选脚本(版本号升序)。 */
    public List<XhsDramaDraft> listDrafts(Long projectId, String ownerId) {
        XhsProject p = owned(projectId, ownerId);
        return draftRepo.findByProjectIdOrderByVersionNoAsc(p.getId());
    }

    /**
     * 用户在线编辑某版候选:部分更新 narration/scenes/characterProfile/styleLock。
     * 校验所属 project 的 ownerId;非本主体的 draft 一律 403。
     */
    public XhsDramaDraft updateDraft(Long draftId, String ownerId, String characterProfile,
                                     String styleLock, String narration, List<String> scenes) {
        XhsDramaDraft d = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("draft 不存在: " + draftId));
        XhsProject p = repo.findById(d.getProjectId())
                .orElseThrow(() -> new IllegalStateException("draft 所属项目不存在"));
        if (!p.getOwnerId().equals(ownerId)) {
            throw new io.llmnote.auth.ForbiddenException("无权编辑此候选");
        }
        if (characterProfile != null) d.setCharacterProfile(characterProfile.trim());
        if (styleLock != null) d.setStyleLock(styleLock.trim());
        if (narration != null) d.setNarration(cleanNarration(narration));
        if (scenes != null) {
            try {
                d.setScenes(objectMapper.writeValueAsString(scenes));
            } catch (Exception e) {
                throw new IllegalArgumentException("scenes 序列化失败: " + e.getMessage());
            }
        }
        return draftRepo.save(d);
    }

    /**
     * 标记用户选定的候选版。仅在 DRAFTS_READY/PENDING/FAILED 时允许;RENDERING 中拒绝。
     * 选完后不会自动启动渲染,等用户点「开始生成短剧」。
     * 放开 PENDING:用户可以提前锁定一版(比如 LLM 限流 v2/v3 一直卡时,先把 v1 选上编辑)
     * — 后续 v2/v3 写库是 upsert by version_no,选中的 v1 内容不会被覆盖。
     */
    public XhsProject chooseDraft(Long projectId, String ownerId, Long draftId) {
        XhsProject p = owned(projectId, ownerId);
        String s = p.getDramaStatus();
        if ("RENDERING".equals(s) || "DONE".equals(s)) {
            throw new IllegalStateException("当前状态(" + s + ")不允许选版");
        }
        if (draftId == null) {
            throw new IllegalArgumentException("draftId 不能为空");
        }
        XhsDramaDraft d = draftRepo.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("draft 不存在: " + draftId));
        if (!d.getProjectId().equals(p.getId())) {
            throw new io.llmnote.auth.ForbiddenException("draft 不属于本项目");
        }
        p.setChosenDraftId(draftId);
        return repo.save(p);
    }

    /**
     * 启动短剧渲染:从已选中的 draft 读 narration + scenes,拼上 characterProfile/styleLock 前缀,
     * 调 {@link #genDramaAsync} 逐段 CogVideoX 渲染。必须先 {@link #chooseDraft};RENDERING 中拒绝。
     */
    public XhsProject renderDrama(Long id, String ownerId) {
        XhsProject p = owned(id, ownerId);
        if (p.getChosenDraftId() == null) {
            throw new IllegalStateException("未选择候选版本,请先挑 1 版再生成");
        }
        if ("RENDERING".equals(p.getDramaStatus())) {
            throw new IllegalStateException("正在渲染中,不能再次启动");
        }
        // 校验选中的 draft 至少有 6 段非空分镜(用户可手动补全失败版,补不够时拒绝)
        XhsDramaDraft d = draftRepo.findById(p.getChosenDraftId()).orElse(null);
        if (d == null) {
            throw new IllegalStateException("选中的候选版不存在,请重选");
        }
        int filled = 0;
        try {
            List<String> raw = (d.getScenes() == null || d.getScenes().isBlank()) ? List.of()
                    : objectMapper.readValue(d.getScenes(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            for (String s : raw) {
                if (s != null && !s.trim().isEmpty()) filled++;
            }
        } catch (Exception ignore) { /* 解析失败时视为 0 段 */ }
        if (filled < DRAMA_SCENES) {
            throw new IllegalStateException("选中的版本分镜不足 " + DRAMA_SCENES + " 段(当前 " + filled + " 段),请补全后再渲染");
        }
        p.setDramaStatus("RENDERING");
        p.setDramaPath(null);
        p.setDramaSegmentsDone(0);
        p.setErrorMsg(null);
        repo.save(p);
        selfProvider.getObject().genDramaAsync(p.getId());
        return p;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > 255 ? t.substring(0, 255) : t);
    }

    /**
     * 每日凌晨清理失败的视频作品:删除该项目视频目录下的中间/半成品文件(seg_*.mp4/narration.mp3/
     * segs.mp4/concat.txt/video.mp4),并清空 video_path/video_status,使前端彻底不残留失败视频。
     * 只针对 videoStatus=FAILED,不影响配图与文案。
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void cleanupFailedVideos() {
        List<XhsProject> failed = repo.findByVideoStatus("FAILED");
        if (failed.isEmpty()) return;
        int files = 0, rows = 0;
        for (XhsProject p : failed) {
            try {
                Path dir = Paths.get(props.getStorage().getImageDir(), safe(p.getOwnerId()), String.valueOf(p.getId()))
                        .toAbsolutePath().normalize();
                if (Files.isDirectory(dir)) {
                    try (var s = Files.list(dir)) {
                        for (Path f : s.toList()) {
                            String n = f.getFileName().toString();
                            if (n.equals("video.mp4") || n.equals("segs.mp4") || n.equals("concat.txt")
                                    || n.equals("narration.mp3") || n.startsWith("seg_")
                                    || n.equals("drama.mp4") || n.equals("drama_narration.mp3") || n.startsWith("drama_seg_")) {
                                if (Files.deleteIfExists(f)) files++;
                            }
                        }
                    }
                }
                p.setVideoPath(null);
                p.setVideoStatus(null);
                repo.save(p);
                rows++;
            } catch (Exception e) {
                log.warn("清理失败视频作品出错 id={}: {}", p.getId(), e.getMessage());
            }
        }
        log.info("每日清理失败视频作品:项目 {} 个,删除文件 {} 个", rows, files);
    }

    public XhsProject updatePublishStatus(Long id, String ownerId, String publishStatus) {
        XhsProject p = owned(id, ownerId);
        p.setPublishStatus(normalizePublish(publishStatus));
        return repo.save(p);
    }

    public void delete(Long id, String ownerId) {
        XhsProject p = owned(id, ownerId);
        repo.delete(p);
    }

    public List<XhsProject> list(String ownerId) {
        return repo.findByOwnerIdOrderByIdDesc(ownerId);
    }

    public XhsProject get(Long id, String ownerId) {
        return owned(id, ownerId);
    }

    // ---- 异步阶段 ----

    @Async
    public void genTitlesAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        try {
            String system = "你是资深的小红书内容策划。请使用联网搜索了解该方向的近期热点与用户关注点,"
                    + "产出高点击率的候选标题。严格只输出一个 JSON 字符串数组(如 [\"标题1\",\"标题2\"]),"
                    + "不要 markdown、不要代码块、不要多余文字。标题需口语化、有钩子、含适当 emoji,简体中文。";
            String user = "方向:「" + p.getDirection() + "」。请给出 " + TITLE_COUNT + " 个不同角度的小红书爆款标题。";
            String raw = searchComplete(system, user);
            List<String> titles = parseTitles(raw);
            if (titles.isEmpty()) { fail(p, "未能生成标题,请重试。"); return; }
            p.setTitleOptions(objectMapper.writeValueAsString(titles));
            p.setStatus("TITLES_DONE");
            repo.save(p);
            log.info("xhs titles done: id={} n={}", id, titles.size());
        } catch (Exception e) {
            log.error("xhs genTitles failed: id={}", id, e);
            fail(p, e.getMessage());
        }
    }

    @Async
    public void researchAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        try {
            String system = "你是小红书内容研究员。请使用联网搜索,围绕给定标题收集真实、最新的可用素材,"
                    + "严禁编造。输出简体中文纯文本长文(可用小标题分段),涵盖:核心卖点/干货要点、"
                    + "常见误区、真实数据或案例、用户可能关心的问题。不要输出 markdown 代码块。";
            String user = "标题:「" + p.getChosenTitle() + "」(方向:" + p.getDirection() + ")。请汇总一篇 600-1200 字的素材长文。";
            String research = searchComplete(system, user);
            if (research == null || research.isBlank()) { fail(p, "未能搜集到素材,请重试。"); return; }
            p.setResearch(research.trim());
            p.setStatus("RESEARCH_DONE");
            repo.save(p);
            log.info("xhs research done: id={} chars={}", id, research.length());
        } catch (Exception e) {
            log.error("xhs research failed: id={}", id, e);
            fail(p, e.getMessage());
        }
    }

    @Async
    public void writeCopyAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        try {
            String system = styleSystem(p.getStyle());
            String user = "标题:「" + p.getChosenTitle() + "」\n\n=== 素材 ===\n"
                    + (p.getResearch() == null ? "" : p.getResearch())
                    + "\n\n请据此产出一篇完整的小红书笔记文案:第一行是吸睛标题(可与上面标题不同,更抓人),"
                    + "然后是正文(合理换行、穿插 emoji、有节奏感),最后一行给出 5-8 个 # 话题标签。只输出文案本身。";
            String copy = chat.complete(system, user);
            if (copy == null || copy.isBlank()) { fail(p, "未能生成文案,请重试。"); return; }
            p.setCopyText(copy.trim());
            p.setStatus("COPY_DONE");
            repo.save(p);
            log.info("xhs copy done: id={} style={} chars={}", id, p.getStyle(), copy.length());
        } catch (Exception e) {
            log.error("xhs writeCopy failed: id={}", id, e);
            fail(p, e.getMessage());
        }
    }

    @Async
    public void genImagesAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        try {
            List<String> prompts = imagePrompts(p.getCopyText());
            if (prompts.isEmpty()) { fail(p, "未能生成配图描述,请重试。"); return; }

            Path dir = Paths.get(props.getStorage().getImageDir(), safe(p.getOwnerId()), String.valueOf(id))
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);

            // 2026-08-18 B 阶段:从串行改并发。MAX_IMAGES=3,Semaphore(2) 限流避免 wanx 429;
            // 每张图独立 try,失败不影响其他。用 indexed CompletableFuture 收集,保持 0/1/2.png 顺序稳定。
            Semaphore gate = new Semaphore(2);
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < prompts.size(); i++) {
                final int idx = i;
                final String prompt = prompts.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        gate.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    try {
                        String url = synthImage(prompt);
                        if (url == null) return null;
                        Path out = dir.resolve("img_" + idx + ".png");
                        try (var in = URI.create(url).toURL().openStream()) {
                            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        return out.toString();
                    } catch (Exception e) {
                        log.warn("xhs image idx={} failed: {}", idx, e.getMessage());
                        return null;
                    } finally {
                        gate.release();
                    }
                }));
            }
            List<String> paths = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
            if (paths.isEmpty()) { fail(p, "配图生成失败,请重试。"); return; }
            p.setImagePaths(objectMapper.writeValueAsString(paths));
            p.setStatus("IMAGES_DONE");
            repo.save(p);
            log.info("xhs images done: id={} n={} (parallel)", id, paths.size());
        } catch (Exception e) {
            log.error("xhs genImages failed: id={}", id, e);
            fail(p, e.getMessage());
        }
    }

    /**
     * 视频阶段:文案 → LLM 出口播文案 + N 条镜头 prompt → CogVideoX-flash 逐段生成 5s 竖屏无声片段
     * → cosyvoice 合成口播 mp3 → ffmpeg 拼接 + 铺配音 → 成片 mp4。任一镜头失败用剩余片段兜底。
     */
    @Async
    public void genVideoAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        Path dir = null;
        try {
            p.setVideoStatus("RENDERING");
            repo.save(p);

            VideoScript vs = videoScript(p.getCopyText());
            if (vs == null || vs.scenes.isEmpty()) { failVideo(p, "未能生成视频镜头,请重试。"); return; }

            dir = Paths.get(props.getStorage().getImageDir(), safe(p.getOwnerId()), String.valueOf(id))
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);

            // 1) 逐段生成 5s 竖屏无声片段
            List<Path> segs = new ArrayList<>();
            for (int i = 0; i < vs.scenes.size(); i++) {
                String taskId = mediaClient.submitVideo(vs.scenes.get(i), VIDEO_SIZE, false);
                if (taskId == null) { log.warn("xhs video seg {} 提交失败 id={}", i, id); continue; }
                String url = mediaClient.pollVideo(taskId, 40, 5000);
                if (url == null) { log.warn("xhs video seg {} 轮询无结果 id={}", i, id); continue; }
                Path seg = dir.resolve("seg_" + i + ".mp4");
                try (var in = URI.create(url).toURL().openStream()) {
                    Files.copy(in, seg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                segs.add(seg);
            }
            if (segs.isEmpty()) { failVideo(p, "视频片段生成失败,请重试。"); return; }

            // 2) 口播 TTS
            Path narration = null;
            if (vs.narration != null && !vs.narration.isBlank()) {
                narration = dir.resolve("narration.mp3");
                if (!ttsToFile(vs.narration, narration)) narration = null;
            }

            // 3) ffmpeg 拼接 + 铺配音
            Path out = dir.resolve("video.mp4");
            muxVideo(dir, segs, narration, out);

            p.setVideoPath(out.toString());
            p.setVideoStatus("DONE");
            p.setStatus("VIDEO_DONE");
            repo.save(p);
            log.info("xhs video done: id={} segs={} narration={}", id, segs.size(), narration != null);
        } catch (Exception e) {
            log.error("xhs genVideo failed: id={}", id, e);
            failVideo(p, e.getMessage());
        }
    }

    /**
     * 短剧阶段:文案 → LLM 出一个「连续剧情」的 {@value #DRAMA_SCENES} 段分镜脚本(统一主角/场景,起承转合)
     * + 旁白口播 → CogVideoX-flash 逐段生成 5s 竖屏无声片段。与普通视频不同:
     * <b>任一段遇 429/超时/失败都等待后重试,直到该段成功;所有分镜都生成成功后才 ffmpeg 合并铺配音</b>,
     * 保证短剧剧情不缺段。全部尝试用尽仍失败才判定失败。
     */
    @Async
    public void genDramaAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        Path dir = null;
        try {
            p.setDramaStatus("RENDERING");
            p.setDramaSegmentsDone(0);
            repo.save(p);

            // 渲染脚本来源:有 chosenDraftId → 从选中的候选版读(注入 characterProfile/styleLock 前缀
            // 强制跨镜头角色/风格一致);无 → 兼容老项目,直接调旧 dramaScript 兜底。
            VideoScript vs;
            if (p.getChosenDraftId() != null) {
                XhsDramaDraft d = draftRepo.findById(p.getChosenDraftId()).orElse(null);
                if (d == null) { failDrama(p, "选中的候选版不存在,请重选。"); return; }
                vs = loadVideoScriptFromDraft(d);
            } else {
                vs = dramaScript(p.getCopyText(), p.getChosenTitle());
            }
            if (vs == null || vs.scenes.isEmpty()) { failDrama(p, "未能生成短剧分镜,请重试。"); return; }

            dir = Paths.get(props.getStorage().getImageDir(), safe(p.getOwnerId()), String.valueOf(id))
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);

            // 1) 逐段生成 5s 竖屏无声片段:每段等待重试到成功,任一段最终失败则整部短剧失败(不缺段)
            //    每段成功后自增 dramaSegmentsDone,前端进度条 + 段位列表用
            List<Path> segs = new ArrayList<>();
            for (int i = 0; i < vs.scenes.size(); i++) {
                Path seg = dir.resolve("drama_seg_" + i + ".mp4");
                boolean ok = renderSegmentWithRetry(vs.scenes.get(i), seg, id, i);
                if (!ok) {
                    failDrama(p, "第 " + (i + 1) + " 段分镜多次重试仍失败(可能免费额度限流),请稍后重试。");
                    return;
                }
                segs.add(seg);
                p.setDramaSegmentsDone(i + 1);
                repo.save(p);
            }

            // 2) 旁白 TTS
            Path narration = null;
            if (vs.narration != null && !vs.narration.isBlank()) {
                narration = dir.resolve("drama_narration.mp3");
                if (!ttsToFile(vs.narration, narration)) narration = null;
            }

            // 3) ffmpeg 拼接 + 铺配音
            Path out = dir.resolve("drama.mp4");
            muxVideo(dir, segs, narration, out);

            p.setDramaPath(out.toString());
            p.setDramaStatus("DONE");
            repo.save(p);
            log.info("xhs drama done: id={} segs={} narration={}", id, segs.size(), narration != null);
        } catch (Exception e) {
            log.error("xhs genDrama failed: id={}", id, e);
            failDrama(p, e.getMessage());
        }
    }

    /**
     * 生成单段短剧视频并落盘到 {@code seg},失败(含 429 限流、轮询无结果)按线性递增等待重试,
     * 最多 {@value #DRAMA_SEG_MAX_ATTEMPTS} 次。全部用尽仍失败返回 false。
     */
    private boolean renderSegmentWithRetry(String scene, Path seg, Long id, int idx) {
        for (int attempt = 1; attempt <= DRAMA_SEG_MAX_ATTEMPTS; attempt++) {
            try {
                String taskId = mediaClient.submitVideo(scene, VIDEO_SIZE, false);
                if (taskId != null) {
                    String url = mediaClient.pollVideo(taskId, 60, 5000);
                    if (url != null) {
                        try (var in = URI.create(url).toURL().openStream()) {
                            Files.copy(in, seg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        log.info("xhs drama seg {} 成功 id={} attempt={}", idx, id, attempt);
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("xhs drama seg {} 第 {} 次异常 id={}: {}", idx, attempt, id, e.getMessage());
            }
            if (attempt < DRAMA_SEG_MAX_ATTEMPTS) {
                long wait = DRAMA_SEG_RETRY_BASE_MS * attempt;
                log.warn("xhs drama seg {} 第 {} 次未成功 id={},等待 {}ms 后重试", idx, attempt, id, wait);
                try { Thread.sleep(wait); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 生成 1 版候选脚本(短剧「3 版候选」改造为 1 版后):
     * LLM 调一次,落 1 行到 xhs_drama_draft,生成成功自动设为 chosenDraftId。
     * 失败也落行(error_msg 写明原因),用户可手动补全 6 段后渲染。
     */
    @Async
    public void genDraftsAsync(Long id) {
        XhsProject p = repo.findById(id).orElse(null);
        if (p == null) return;
        try {
            // 同 project 重试场景:用已存在的 v1 行 upsert
            XhsDramaDraft row = draftRepo.findByProjectIdOrderByVersionNoAsc(id).stream()
                    .filter(x -> x.getVersionNo() != null && x.getVersionNo() == 1)
                    .findFirst().orElse(null);
            XhsDramaDraft fresh;
            try {
                fresh = draftScript(id, p, 1);
            } catch (Exception e) {
                log.warn("xhs draftScript id={} 失败: {}", id, e.getMessage());
                fresh = new XhsDramaDraft();
                fresh.setProjectId(id);
                fresh.setVersionNo(1);
                String msg = e.getMessage() == null ? "生成失败" : e.getMessage();
                fresh.setErrorMsg(msg.length() > 2000 ? msg.substring(0, 2000) : msg);
            }
            if (row == null) { row = fresh; row.setProjectId(id); }
            else {
                row.setCharacterProfile(fresh.getCharacterProfile());
                row.setStyleLock(fresh.getStyleLock());
                row.setNarration(fresh.getNarration());
                row.setScenes(fresh.getScenes());
                row.setErrorMsg(fresh.getErrorMsg());
            }
            try { row = draftRepo.save(row); } catch (Exception e) {
                log.warn("xhs genDrafts 落库失败 id={}: {}", id, e.getMessage());
            }
            boolean ok = row.getErrorMsg() == null;
            log.info("xhs genDrafts id={} done ok={}", id, ok);
            if (ok) {
                // 1 版模式下:生成成功自动选为 chosen,前端无需「选这个版本」步骤
                p.setChosenDraftId(row.getId());
                p.setDramaStatus("DRAFTS_READY");
                p.setErrorMsg(null);
            } else {
                p.setDramaStatus("FAILED");
                p.setErrorMsg(row.getErrorMsg());
            }
            repo.save(p);
        } catch (Exception e) {
            log.error("xhs genDrafts failed: id={}", id, e);
            failDrama(p, e.getMessage());
        }
    }

    /**
     * 单版候选脚本生成:用 imported prompt 模板的 4 入参(genre/character/arc/tone 都可选),
     * 配同主角+同风格的硬性要求,产出 characterProfile/styleLock/narration/6 段 scenes。
     * 不足 6 段不算成功(标 error_msg,前端会展示灰卡);不静默兜底。
     */
    private XhsDramaDraft draftScript(Long projectId, XhsProject p, int versionNo) {
        XhsDramaDraft d = new XhsDramaDraft();
        d.setProjectId(projectId);
        d.setVersionNo(versionNo);
        String system = "你是短剧导演兼编剧。请把用户的剧情想法变成一部竖屏(9:16)微短剧的制作脚本。"
                + "严格只输出一个 JSON 对象,不要 markdown、不要多余文字,schema 如下:"
                + "{\"characterProfile\":\"主角固定外貌/身份/穿着的一句话描述,会被逐字注入每段画面 prompt 保持角色一致\","
                + "\"styleLock\":\"全片统一的画面风格关键词(色调/光线/镜头质感/画风)\","
                + "\"narration\":\"简体中文旁白口播稿,96~144 字,起承转合,口语连贯,禁 emoji/#/markdown/括号备注\","
                + "\"scenes\":[\"镜头1\",\"镜头2\",\"镜头3\",\"镜头4\",\"镜头5\",\"镜头6\"]}。"
                + "硬性要求:1) scenes 恰好 " + DRAMA_SCENES + " 条,起(开场)/承(推进)/转(冲突)/合(结局),"
                + "时间与情节连续,不要跳变;2) characterProfile 必须具体且可复用,每条 scene 开头都要复述同样的主角外貌特征,"
                + "并在画面描述里加入 styleLock 的关键词,最大化跨镜头角色/风格一致性;"
                + "3) 每条 scene 约 5 秒画面,含主体/动作/场景/光线/镜头语言/竖屏构图,可直接用于文生视频;"
                + "4) genre/mainCharacter/storyArc/tone 任一为空时由你根据 theme 自行补一个合理设定;"
                + "5) 旁白与每个分镜都必须紧扣用户给的 theme,不得绕开或替换主题。";
        String user = "剧情主题:「" + nullToDash(p.getDirection()) + "」\n"
                + "类型: " + nullToDash(p.getGenre()) + "\n"
                + "主角: " + nullToDash(p.getMainCharacter()) + "\n"
                + "故事弧线: " + nullToDash(p.getStoryArc()) + "\n"
                + "基调: " + nullToDash(p.getTone());
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String raw = chat.complete(system, user);
                String json = extractJsonObject(raw);
                if (json != null) {
                    JsonNode node = objectMapper.readTree(json);
                    d.setCharacterProfile(safeStr(node.path("characterProfile").asText("")));
                    d.setStyleLock(safeStr(node.path("styleLock").asText("")));
                    d.setNarration(cleanNarration(node.path("narration").asText("")));
                    List<String> scenes = new ArrayList<>();
                    JsonNode arr = node.path("scenes");
                    if (arr.isArray()) {
                        for (JsonNode s : arr) {
                            String t = s.asText("").trim();
                            if (!t.isBlank()) scenes.add(t);
                            if (scenes.size() >= DRAMA_SCENES) break;
                        }
                    }
                    if (scenes.size() == DRAMA_SCENES) {
                        d.setScenes(objectMapper.writeValueAsString(scenes));
                        d.setErrorMsg(null);
                        return d;
                    }
                }
            } catch (Exception e) {
                log.warn("xhs draftScript v{} 第 {} 次失败: {}", versionNo, attempt + 1, e.getMessage());
            }
            try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
        // 兜底:已写入的部分字段保留,只标 error_msg,前端按灰卡展示
        if (d.getScenes() == null) {
            try { d.setScenes(objectMapper.writeValueAsString(new ArrayList<String>())); }
            catch (Exception ignored) { d.setScenes("[]"); }
        }
        if (d.getNarration() == null) d.setNarration("");
        d.setErrorMsg("分镜不足 6 段,可手动补全后再次保存");
        return d;
    }

    private static String safeStr(String s) {
        if (s == null) return "";
        return s.length() > 1024 ? s.substring(0, 1024) : s;
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "由你决定" : s;
    }

    /**
     * 把选中的 draft 转成渲染用 VideoScript:把 characterProfile + styleLock 拼成统一前缀,
     * 逐字注入每段 scene,这是跨镜头角色/风格一致性的关键修复点。draft 不存在或 scenes 解析失败
     * 返回空 scenes(由 genDramaAsync 判定失败)。
     */
    private VideoScript loadVideoScriptFromDraft(XhsDramaDraft d) {
        VideoScript vs = new VideoScript();
        vs.narration = d.getNarration() == null ? "" : d.getNarration();
        String profile = d.getCharacterProfile() == null ? "" : d.getCharacterProfile().trim();
        String style = d.getStyleLock() == null ? "" : d.getStyleLock().trim();
        StringBuilder pfx = new StringBuilder();
        if (!profile.isEmpty()) pfx.append(profile).append(", ");
        if (!style.isEmpty()) pfx.append(style).append(", ");
        String prefix = pfx.toString();
        try {
            List<String> raw = (d.getScenes() == null || d.getScenes().isBlank()) ? List.of()
                    : objectMapper.readValue(d.getScenes(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            for (String s : raw) {
                if (s == null) continue;
                String t = s.trim();
                if (t.isEmpty()) continue;
                vs.scenes.add(prefix.isEmpty() ? t : prefix + t);
            }
        } catch (Exception e) {
            log.warn("xhs loadVideoScriptFromDraft 解析 scenes 失败 draftId={}: {}", d.getId(), e.getMessage());
        }
        return vs;
    }

    /**
     * 让 LLM 从文案产出一部「连续剧情」短剧的旁白口播 + {@value #DRAMA_SCENES} 段分镜(JSON)。
     * 强调:统一主角与场景、镜头间时间/情节连续(起承转合),各段画面描述里都带上主角外貌以尽量保持一致。
     * 带退避重试以躲 GLM 限流;失败回退到普通分镜。
     */
    private VideoScript dramaScript(String copy, String title) {
        VideoScript vs = new VideoScript();
        String system = "你是短剧导演兼编剧。请根据给定文案/主题,创作一部竖屏(9:16)微短剧的旁白口播稿与连续分镜。"
                + "严格只输出一个 JSON 对象,格式:{\"narration\":\"旁白口播稿\",\"scenes\":[\"镜头1画面\",\"镜头2画面\", ...]}。"
                + "要求:1) scenes 恰好 " + DRAMA_SCENES + " 条,构成一个有起承转合的完整小故事,镜头之间时间与情节连续;"
                + "2) 全程围绕同一位主角,请在每条镜头描述开头都复述主角的固定外貌特征(如「短发白衬衫的年轻女子」),"
                + "并保持场景风格一致,以尽量让 AI 生成的人物前后一致;"
                + "3) 每条镜头具体可用于文生视频(含主体、动作、场景、光线、镜头语言、竖屏构图),约 5 秒一个画面;"
                + "4) narration 为可直接朗读的简体中文旁白(" + DRAMA_SCENES * 16 + "~" + DRAMA_SCENES * 24 + " 字,"
                + "口语连贯、有故事感与情绪,纯文字,绝不能出现 emoji、#话题、markdown 符号、序号、括号备注)。"
                + "不要多余文字、不要 markdown 代码块。";
        String user = "短剧主题:「" + (title == null ? "" : title) + "」\n\n参考文案:\n" + (copy == null ? "" : copy);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String raw = chat.complete(system, user);
                String json = extractJsonObject(raw);
                if (json != null) {
                    JsonNode node = objectMapper.readTree(json);
                    vs.narration = cleanNarration(node.path("narration").asText(""));
                    JsonNode scenes = node.path("scenes");
                    vs.scenes.clear();
                    if (scenes.isArray()) {
                        for (JsonNode s : scenes) {
                            String t = s.asText("").trim();
                            if (!t.isBlank()) vs.scenes.add(t);
                            if (vs.scenes.size() >= DRAMA_SCENES) break;
                        }
                    }
                }
                if (vs.scenes.size() == DRAMA_SCENES) break; // 需要满段数才算成功
            } catch (Exception e) {
                log.warn("xhs dramaScript 第 {} 次失败: {}", attempt + 1, e.getMessage());
            }
            try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        // 兜底:分镜不足时用文案自造,补齐到 DRAMA_SCENES 段
        if (vs.scenes.size() < DRAMA_SCENES) {
            for (String s : fallbackScenes(copy)) {
                if (vs.scenes.size() >= DRAMA_SCENES) break;
                if (!vs.scenes.contains(s)) vs.scenes.add(s);
            }
            int seq = 1;
            while (vs.scenes.size() < DRAMA_SCENES) {
                vs.scenes.add("竖屏 9:16 连续剧情画面,第 " + (seq++) + " 幕,主角在同一场景中继续故事,情绪递进,电影质感光线");
            }
        }
        if (vs.narration.isBlank()) {
            vs.narration = fallbackNarration(copy, vs.scenes.size());
        }
        return vs;
    }

    /**
     * 短剧阶段失败:只标记 {@code dramaStatus=FAILED} 并清 dramaPath,不动项目主 status 与普通视频。
     */
    private void failDrama(XhsProject p, String msg) {
        if (p == null) return;
        p.setDramaStatus("FAILED");
        p.setDramaPath(null);
        p.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        repo.save(p);
    }

    // ---- 工具方法 ----

    /** 口播文案 + 镜头 prompt。 */
    private static final class VideoScript {
        String narration = "";
        List<String> scenes = new ArrayList<>();
    }

    /** 让 LLM 从文案产出口播文案与 2~4 条镜头画面 prompt(JSON)。带退避重试以躲 GLM 限流。 */
    private VideoScript videoScript(String copy) {
        VideoScript vs = new VideoScript();
        String system = "你是短视频导演。请根据小红书文案,产出一条竖屏短视频的口播稿与分镜。"
                + "严格只输出一个 JSON 对象,格式:{\"narration\":\"口播稿\",\"scenes\":[\"镜头1画面描述\",\"镜头2画面描述\"]}。"
                + "narration 为可直接朗读的简体中文口播稿(" + MIN_SCENES * 18 + "~" + MAX_SCENES * 22 + " 字,"
                + "口语连贯、有感染力,纯文字,绝不能出现 emoji、#话题、markdown 符号、序号、括号备注);"
                + "scenes 为 " + MIN_SCENES + "~" + MAX_SCENES + " 条镜头画面描述,每条具体可用于文生视频"
                + "(含主体、场景、动作、风格、光线、竖屏构图),各镜头画面应有区分度。不要多余文字、不要 markdown 代码块。";
        String user = "文案:\n" + (copy == null ? "" : copy);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String raw = chat.complete(system, user);
                String json = extractJsonObject(raw);
                if (json != null) {
                    JsonNode node = objectMapper.readTree(json);
                    vs.narration = cleanNarration(node.path("narration").asText(""));
                    JsonNode scenes = node.path("scenes");
                    if (scenes.isArray()) {
                        for (JsonNode s : scenes) {
                            String t = s.asText("").trim();
                            if (!t.isBlank()) vs.scenes.add(t);
                            if (vs.scenes.size() >= MAX_SCENES) break;
                        }
                    }
                }
                if (!vs.scenes.isEmpty()) break; // 成功
            } catch (Exception e) {
                log.warn("xhs videoScript 第 {} 次失败: {}", attempt + 1, e.getMessage());
            }
            try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        // 兜底:LLM 失败时从文案自造多条镜头,避免只有 1 个 5s 空镜
        if (vs.scenes.isEmpty()) {
            vs.scenes.addAll(fallbackScenes(copy));
        }
        if (vs.narration.isBlank()) {
            vs.narration = fallbackNarration(copy, vs.scenes.size());
        }
        return vs;
    }

    /** 清洗口播文本:去掉 emoji、#话题、markdown 符号、序号项目符,避免 TTS 逐字念出。 */
    private String cleanNarration(String raw) {
        if (raw == null) return "";
        String s = raw
                .replaceAll("#\\S+", " ")                                   // 话题标签
                .replaceAll("[*_`>#~]", " ")                                // markdown 标记
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\p{So}\\uFE0F\\u20E3]", " ") // emoji/符号
                .replaceAll("^[\\s\\-•·—\\d.、)）]+", " ")                   // 行首序号/项目符
                .replaceAll("[\\r\\n]+", ",")                               // 换行→停顿
                .replaceAll("\\s{2,}", " ")
                .trim();
        return s;
    }

    /** LLM 失败时:按文案切出 2~3 条尽量不同的镜头画面 prompt。 */
    private List<String> fallbackScenes(String copy) {
        List<String> out = new ArrayList<>();
        String base = copy == null ? "" : cleanNarration(copy);
        String[] parts = base.split("[。!?;,\\n]+");
        String[] moods = {"明亮清新的特写", "温暖生活感的中景", "时尚氛围的全景"};
        for (int i = 0; i < moods.length && out.size() < MAX_SCENES; i++) {
            String hint = "";
            for (String p : parts) { if (p.trim().length() >= 6) { hint = p.trim(); break; } }
            if (i < parts.length && parts[i].trim().length() >= 6) hint = parts[i].trim();
            out.add("小红书风格竖屏画面,9:16 构图," + moods[i] + ",主题:"
                    + (hint.isBlank() ? "精致生活" : hint.substring(0, Math.min(40, hint.length()))));
        }
        if (out.isEmpty()) out.add("小红书风格竖屏画面,9:16 构图,精致生活场景");
        return out;
    }

    /** LLM 失败时的口播兜底:清洗 + 按镜头数控制长度 + 句末截断。 */
    private String fallbackNarration(String copy, int sceneCount) {
        String s = cleanNarration(copy);
        if (s.isBlank()) return "";
        int max = Math.max(30, sceneCount * 22); // 每段约 5s ≈ 22 字
        if (s.length() <= max) return s;
        String cut = s.substring(0, max);
        int p = Math.max(cut.lastIndexOf('。'), Math.max(cut.lastIndexOf(','), cut.lastIndexOf('!')));
        return p > 10 ? cut.substring(0, p + 1) : cut;
    }

    /** 从可能包裹代码块的输出里抠出 JSON 对象主体。 */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : null;
    }

    /** cosyvoice 合成口播为 mp3 文件。成功返回 true。 */
    private boolean ttsToFile(String text, Path out) {
        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(props.getDashscope().getApiKey())
                    .model(props.getDashscope().getTtsModel())
                    .voice(props.getDashscope().getTtsVoiceA())
                    .format(TTS_FMT)
                    .build();
            ByteBuffer audio = new SpeechSynthesizer(param, null).call(text);
            if (audio == null || !audio.hasRemaining()) return false;
            byte[] bytes = new byte[audio.remaining()];
            audio.get(bytes);
            try (OutputStream os = Files.newOutputStream(out)) { os.write(bytes); }
            return true;
        } catch (Exception e) {
            log.warn("xhs 口播 TTS 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ffmpeg:先 concat 拼接纯画面(-c copy),再铺 TTS 配音(-shortest 对齐)。
     * 无口播时直接把拼接结果作为成片。concat -c copy 失败时重编码兜底。
     */
    private void muxVideo(Path dir, List<Path> segs, Path narration, Path out) throws Exception {
        Path concatList = dir.resolve("concat.txt");
        StringBuilder sb = new StringBuilder();
        for (Path seg : segs) sb.append("file '").append(seg.getFileName().toString()).append("'\n");
        Files.writeString(concatList, sb.toString(), StandardCharsets.UTF_8);

        Path merged = dir.resolve("segs.mp4");
        boolean ok = runFfmpeg(dir, List.of("-y", "-f", "concat", "-safe", "0",
                "-i", "concat.txt", "-c", "copy", "segs.mp4"));
        if (!ok) {
            // 编码参数不一致时,重编码兜底
            runFfmpegOrThrow(dir, List.of("-y", "-f", "concat", "-safe", "0",
                    "-i", "concat.txt", "-c:v", "libx264", "-pix_fmt", "yuv420p", "segs.mp4"));
        }

        if (narration != null && Files.exists(narration)) {
            runFfmpegOrThrow(dir, List.of("-y", "-i", "segs.mp4", "-i", narration.getFileName().toString(),
                    "-c:v", "copy", "-c:a", "aac", "-shortest", out.getFileName().toString()));
        } else {
            Files.move(merged, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean runFfmpeg(Path workDir, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.addAll(args);
        Process proc = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder tail = new StringBuilder();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (tail.length() < 4000) tail.append(line).append('\n');
        }
        boolean done = proc.waitFor(120, TimeUnit.SECONDS);
        if (!done) { proc.destroyForcibly(); throw new IllegalStateException("ffmpeg 超时"); }
        int code = proc.exitValue();
        if (code != 0) log.warn("ffmpeg exit={} args={} tail={}", code, args, tail);
        return code == 0;
    }

    private void runFfmpegOrThrow(Path workDir, List<String> args) throws Exception {
        if (!runFfmpeg(workDir, args)) throw new IllegalStateException("ffmpeg 执行失败: " + args);
    }

    /**
     * 视频阶段失败:只标记 {@code videoStatus=FAILED},不动项目主 {@code status}
     * (视频只是配图之后的可选第 5 步,失败不应把整个项目判死;失败产物由每日任务清理)。
     * 顺手清掉可能已写入的半成品 video_path。
     */
    private void failVideo(XhsProject p, String msg) {
        if (p == null) return;
        p.setVideoStatus("FAILED");
        p.setVideoPath(null);
        p.setStatus("IMAGES_DONE");
        p.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        repo.save(p);
    }

    /** 让 qwen 把文案压成 1~3 条画面描述 prompt。 */
    private List<String> imagePrompts(String copy) throws Exception {
        String system = "你是小红书配图的美术指导。请根据文案提炼出适合作为封面/配图的画面描述,"
                + "每条为具体、可直接用于文生图的中文描述(含主体、场景、风格、光线、构图)。"
                + "严格只输出一个 JSON 字符串数组,1 到 " + MAX_IMAGES + " 条,不要多余文字。";
        String raw = chat.complete(system, "文案:\n" + (copy == null ? "" : copy));
        List<String> list = parseTitles(raw);
        return list.size() > MAX_IMAGES ? list.subList(0, MAX_IMAGES) : list;
    }

    /** 调通义万相 wanx 文生图,返回临时图片 URL。 */
    private String synthImage(String prompt) {
        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(props.getDashscope().getApiKey())
                    .model(ImageSynthesis.Models.WANX_V1)
                    .prompt(prompt)
                    .n(1)
                    .size(IMG_SIZE)
                    .build();
            ImageSynthesisResult result = new ImageSynthesis().call(param);
            if (result == null || result.getOutput() == null || result.getOutput().getResults() == null) return null;
            for (Map<String, String> m : result.getOutput().getResults()) {
                String url = m.get("url");
                if (url != null && !url.isBlank()) return url;
            }
            return null;
        } catch (Exception e) {
            log.warn("wanx synth failed: {}", e.getMessage());
            return null;
        }
    }

    /** 开启联网搜索的一次性补全(仿 NewsService)。 */
    private String searchComplete(String system, String user) {
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of("enable_search", true))
                .build();
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
        List<ChatResponse> responses = chatModel.stream(messages, List.of(), options).collectList().block();
        StringBuilder sb = new StringBuilder();
        if (responses != null) {
            for (ChatResponse r : responses) {
                if (r.getContent() == null) continue;
                r.getContent().forEach(b -> {
                    if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                });
            }
        }
        return sb.toString();
    }

    /** 从模型输出里解析 JSON 字符串数组,容错代码块与非数组情形。 */
    private List<String> parseTitles(String raw) {
        if (raw == null) return List.of();
        String s = raw.strip();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                List<String> list = objectMapper.readValue(s.substring(start, end + 1),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                List<String> cleaned = new ArrayList<>();
                for (String t : list) if (t != null && !t.isBlank()) cleaned.add(t.trim());
                return cleaned;
            } catch (Exception ignore) { /* fallthrough */ }
        }
        // 回退:按行拆
        List<String> lines = new ArrayList<>();
        for (String line : s.split("\n")) {
            String t = line.replaceFirst("^[\\-*\\d.、\\s\"]+", "").replaceFirst("[\"\\s]+$", "").trim();
            if (!t.isBlank()) lines.add(t);
        }
        return lines;
    }

    private String styleSystem(String style) {
        String base = "你是小红书爆款写手。文案要口语化、有代入感,合理使用 emoji 与换行,结尾给话题标签。只输出文案。";
        return switch (style == null ? "" : style) {
            case "ZHONGCAO" -> base + " 风格:种草安利,真诚分享使用体验,突出优点与推荐理由,营造「必入」冲动。";
            case "DUSHE" -> base + " 风格:毒舌吐槽,犀利幽默、敢说真话,先吐槽再给真实建议,反差感强但不低俗。";
            case "GANHUO" -> base + " 风格:干货教程,条理清晰、可执行,多用步骤/清单/要点,强调实用价值。";
            case "ZHIYU" -> base + " 风格:治愈温柔,舒缓文艺、有情绪价值,文字温暖治愈,给人陪伴感。";
            default -> base;
        };
    }

    private String normalizeStyle(String s) {
        String u = s == null ? "" : s.trim().toUpperCase();
        return switch (u) {
            case "ZHONGCAO", "DUSHE", "GANHUO", "ZHIYU" -> u;
            default -> "ZHONGCAO";
        };
    }

    private String normalizePublish(String s) {
        String u = s == null ? "" : s.trim().toUpperCase();
        return switch (u) {
            case "DRAFT", "READY", "PUBLISHED" -> u;
            default -> "DRAFT";
        };
    }

    /** owner_id 安全化为目录名(u:1 → u_1,g:xxx → g_xxx)。 */
    private String safe(String ownerId) {
        return ownerId == null ? "unknown" : ownerId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private XhsProject owned(Long id, String ownerId) {
        return repo.findById(id)
                .filter(p -> p.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("xhs project not found: " + id));
    }

    private void fail(XhsProject p, String msg) {
        if (p == null) return;
        p.setStatus("FAILED");
        p.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        repo.save(p);
    }
}
