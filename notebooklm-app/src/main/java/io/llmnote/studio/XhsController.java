package io.llmnote.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.auth.Principal;
import io.llmnote.config.NotebookLmProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 小红书文案生成工作台入口。分步提交(方向→标题→素材→风格文案→配图)各触发一个异步阶段,
 * 前端逐段轮询 GET /{id}。所有操作按 principal.ownerId() 做数据隔离。
 */
@RestController
@RequestMapping("/api/studio/xhs")
@RequiredArgsConstructor
@Slf4j
public class XhsController {

    private final XhsService xhsService;
    private final ObjectMapper objectMapper;
    private final NotebookLmProperties props;

    /** Step1:提交方向,异步联网扩写候选标题。 */
    @PostMapping
    public XhsProject create(@RequestBody CreateReq req, Principal principal) {
        return xhsService.create(principal.ownerId(), req == null ? null : req.getDirection());
    }

    /** 列出该主体的全部项目(最新在前)。 */
    @GetMapping
    public List<XhsProject> list(Principal principal) {
        return xhsService.list(principal.ownerId());
    }

    /** 查询单个项目(轮询用)。 */
    @GetMapping("/{id}")
    public XhsProject get(@PathVariable Long id, Principal principal) {
        return xhsService.get(id, principal.ownerId());
    }

    /** Step2:选定标题,异步联网搜集素材。 */
    @PostMapping("/{id}/research")
    public XhsProject research(@PathVariable Long id, @RequestBody TitleReq req, Principal principal) {
        return xhsService.research(id, principal.ownerId(), req == null ? null : req.getTitle());
    }

    /** Step3:选定风格,异步生成小红书文案。 */
    @PostMapping("/{id}/copy")
    public XhsProject copy(@PathVariable Long id, @RequestBody StyleReq req, Principal principal) {
        return xhsService.writeCopy(id, principal.ownerId(), req == null ? null : req.getStyle());
    }

    /** Step4:异步生成配图。 */
    @PostMapping("/{id}/images")
    public XhsProject images(@PathVariable Long id, Principal principal) {
        return xhsService.genImages(id, principal.ownerId());
    }

    /** Step5:异步生成配音短视频(分段 CogVideoX + TTS + ffmpeg 合成)。 */
    @PostMapping("/{id}/video")
    public XhsProject video(@PathVariable Long id, Principal principal) {
        return xhsService.genVideo(id, principal.ownerId());
    }

    /** 短剧:连续剧情多分镜,逐段生成(遇限流等待重试到成功)后合并成片。 */
    @PostMapping("/{id}/drama")
    public XhsProject drama(@PathVariable Long id, Principal principal) {
        return xhsService.genDrama(id, principal.ownerId());
    }

    /** 短剧独立工作台:用主题(+ 4 个可选结构化输入)创建项目,异步生成 1 版候选脚本(成功自动选中)。 */
    @PostMapping("/drama")
    public XhsProject createDrama(@RequestBody DramaReq req, Principal principal) {
        DramaReq r = req == null ? new DramaReq() : req;
        return xhsService.createDrama(principal.ownerId(), r.getTheme(),
                r.getGenre(), r.getMainCharacter(), r.getStoryArc(), r.getTone());
    }

    /** 短剧工作台:对已存在的 FAILED 项目重跑脚本生成(保留主题 + 4 个输入),不必重提表单。 */
    @PostMapping("/{id}/drama/regenerate")
    public XhsProject regenerateDrama(@PathVariable Long id, Principal principal) {
        return xhsService.regenerateDrama(id, principal.ownerId());
    }

    /** 短剧工作台:列出该项目的 3 版候选脚本(版本号升序)。 */
    @GetMapping("/{id}/drafts")
    public List<XhsDramaDraft> listDrafts(@PathVariable Long id, Principal principal) {
        return xhsService.listDrafts(id, principal.ownerId());
    }

    /** 短剧工作台:在线编辑某版候选(narration/scenes/characterProfile/styleLock 部分更新)。 */
    @PutMapping("/drafts/{draftId}")
    public XhsDramaDraft updateDraft(@PathVariable Long draftId, @RequestBody DramaDraftUpdateReq req,
                                     Principal principal) {
        DramaDraftUpdateReq r = req == null ? new DramaDraftUpdateReq() : req;
        return xhsService.updateDraft(draftId, principal.ownerId(),
                r.getCharacterProfile(), r.getStyleLock(), r.getNarration(), r.getScenes());
    }

    /** 短剧工作台:标记用户选中的候选版(只标记,不启动渲染)。 */
    @PostMapping("/{id}/drafts/{draftId}/choose")
    public XhsProject chooseDraft(@PathVariable Long id, @PathVariable Long draftId, Principal principal) {
        return xhsService.chooseDraft(id, principal.ownerId(), draftId);
    }

    /** 短剧工作台:启动 CogVideoX 渲染(必须先 chooseDraft,要求 chosenDraftId 非空)。 */
    @PostMapping("/{id}/drama/render")
    public XhsProject renderDrama(@PathVariable Long id, Principal principal) {
        return xhsService.renderDrama(id, principal.ownerId());
    }

    /** 短剧独立工作台:列出该主体的全部短剧项目(最新在前)。 */
    @GetMapping("/drama")
    public List<XhsProject> listDrama(Principal principal) {
        return xhsService.listDrama(principal.ownerId());
    }

    /** 发布管理:切换本地状态(草稿/待发/已发)。 */
    @PutMapping("/{id}/publish")
    public XhsProject publish(@PathVariable Long id, @RequestBody PublishReq req, Principal principal) {
        return xhsService.updatePublishStatus(id, principal.ownerId(), req == null ? null : req.getPublishStatus());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Principal principal) {
        xhsService.delete(id, principal.ownerId());
    }

    /** 展示/下载配图。idx 对应 image_paths JSON 数组下标。 */
    @GetMapping("/{id}/images/{idx}")
    public ResponseEntity<Resource> image(@PathVariable Long id, @PathVariable int idx, Principal principal) {
        XhsProject p = xhsService.get(id, principal.ownerId());
        if (p.getImagePaths() == null) return ResponseEntity.notFound().build();
        List<String> paths;
        try {
            paths = objectMapper.readValue(p.getImagePaths(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
        if (idx < 0 || idx >= paths.size()) return ResponseEntity.notFound().build();
        // 绝对路径直接用;相对路径拼到 imageDir 下(早期 imageDir 变化或历史项目可能存的是相对路径)
        Path stored = Paths.get(paths.get(idx));
        Path path = stored.isAbsolute()
                ? stored
                : Paths.get(props.getStorage().getImageDir()).toAbsolutePath().normalize()
                        .resolve(stored).normalize();
        Resource res = new FileSystemResource(path);
        if (!res.exists()) {
            log.warn("xhs image missing: id={} idx={} stored='{}' resolved='{}'", id, idx, paths.get(idx), path);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"xhs_" + id + "_" + idx + ".png\"")
                .body(res);
    }

    /** 展示/下载成片 mp4。 */
    @GetMapping("/{id}/video")
    public ResponseEntity<Resource> videoFile(@PathVariable Long id, Principal principal) {
        XhsProject p = xhsService.get(id, principal.ownerId());
        if (p.getVideoPath() == null || p.getVideoPath().isBlank()) return ResponseEntity.notFound().build();
        Path stored = Paths.get(p.getVideoPath());
        Path path = stored.isAbsolute()
                ? stored
                : Paths.get(props.getStorage().getImageDir()).toAbsolutePath().normalize()
                        .resolve(stored).normalize();
        Resource res = new FileSystemResource(path);
        if (!res.exists()) {
            log.warn("xhs video missing: id={} stored='{}' resolved='{}'", id, p.getVideoPath(), path);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"xhs_" + id + ".mp4\"")
                .body(res);
    }

    /** 展示/下载短剧成片 mp4。 */
    @GetMapping("/{id}/drama")
    public ResponseEntity<Resource> dramaFile(@PathVariable Long id, Principal principal) {
        XhsProject p = xhsService.get(id, principal.ownerId());
        if (p.getDramaPath() == null || p.getDramaPath().isBlank()) return ResponseEntity.notFound().build();
        Path stored = Paths.get(p.getDramaPath());
        Path path = stored.isAbsolute()
                ? stored
                : Paths.get(props.getStorage().getImageDir()).toAbsolutePath().normalize()
                        .resolve(stored).normalize();
        Resource res = new FileSystemResource(path);
        if (!res.exists()) {
            log.warn("xhs drama missing: id={} stored='{}' resolved='{}'", id, p.getDramaPath(), path);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"xhs_drama_" + id + ".mp4\"")
                .body(res);
    }

    /** 短剧单段视频(0~5):用于渲染中段位预览,只读,idx 越界返 404。 */
    @GetMapping("/{id}/drama/seg/{idx}")
    public ResponseEntity<Resource> dramaSegmentFile(@PathVariable Long id, @PathVariable int idx, Principal principal) {
        if (idx < 0 || idx > 5) return ResponseEntity.notFound().build();
        XhsProject p = xhsService.get(id, principal.ownerId());
        Path dir = Paths.get(props.getStorage().getImageDir(), p.getOwnerId(), String.valueOf(id))
                .toAbsolutePath().normalize();
        Path path = dir.resolve("drama_seg_" + idx + ".mp4");
        Resource res = new FileSystemResource(path);
        if (!res.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"xhs_drama_" + id + "_seg" + idx + ".mp4\"")
                .body(res);
    }

    @Data
    public static class CreateReq {
        private String direction;
    }

    @Data
    public static class TitleReq {
        private String title;
    }

    @Data
    public static class StyleReq {
        private String style;
    }

    @Data
    public static class PublishReq {
        private String publishStatus;
    }

    @Data
    public static class DramaReq {
        private String theme;
        /** 短剧类型(可选):都市/悬疑/治愈/古风… */
        private String genre;
        /** 主角设定(可选):留空时 LLM 兜底 */
        private String mainCharacter;
        /** 故事弧线/走向(可选):留空时 LLM 兜底 */
        private String storyArc;
        /** 基调(可选):温暖治愈/黑色幽默/紧张悬疑… */
        private String tone;
    }

    /** 候选版在线编辑的 partial update DTO:只更新非 null 字段。 */
    @Data
    public static class DramaDraftUpdateReq {
        private String characterProfile;
        private String styleLock;
        private String narration;
        private List<String> scenes;
    }
}
