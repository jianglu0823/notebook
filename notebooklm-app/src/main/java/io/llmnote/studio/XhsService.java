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
import java.util.List;
import java.util.Map;
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
    /** 竖屏 9:16。 */
    private static final String VIDEO_SIZE = "1080x1920";
    private static final SpeechSynthesisAudioFormat TTS_FMT = SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS;

    private final DashScopeChatModel chatModel;
    private final ChatCompletion chat;
    private final NotebookLmProperties props;
    private final ObjectMapper objectMapper;
    private final XhsProjectRepository repo;
    private final ZhipuMediaClient mediaClient;

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
                                    || n.equals("narration.mp3") || n.startsWith("seg_")) {
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

            List<String> paths = new ArrayList<>();
            for (int i = 0; i < prompts.size(); i++) {
                String url = synthImage(prompts.get(i));
                if (url == null) continue;
                Path out = dir.resolve("img_" + i + ".png");
                try (var in = URI.create(url).toURL().openStream()) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                paths.add(out.toString());
            }
            if (paths.isEmpty()) { fail(p, "配图生成失败,请重试。"); return; }
            p.setImagePaths(objectMapper.writeValueAsString(paths));
            p.setStatus("IMAGES_DONE");
            repo.save(p);
            log.info("xhs images done: id={} n={}", id, paths.size());
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
