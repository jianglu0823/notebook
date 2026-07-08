package io.llmnote.studio;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.llm.ChatCompletion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final DashScopeChatModel chatModel;
    private final ChatCompletion chat;
    private final NotebookLmProperties props;
    private final ObjectMapper objectMapper;
    private final XhsProjectRepository repo;

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

    // ---- 工具方法 ----

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
