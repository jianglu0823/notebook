package io.llmnote.world.forum;

import io.llmnote.llm.ZhipuMediaClient;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.AgentProduct;
import io.llmnote.world.AgentProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 论坛帖子异步附件生成(@Async 同 bean 自调用失效,本类独立 bean)。
 * 按 category 决定图片 / 视频数量,生成成功后写 agent_forum_attachment
 * 并同步 insert 一条 agent_product(kind=image/video, ref_post_id=post.id),
 * 这样 /api/world/products(作品馆)无须改就能看到论坛产出的图 / 视频。
 *
 * <p>失败隔离:每个附件独立 try/catch,单条失败不阻塞其他。帖子状态不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumMediaService {

    /** 视频轮询上限 × 间隔,沿用 WorldMediaRunner:50 × 12s ≈ 10 分钟。 */
    private static final int MAX_VIDEO_POLLS = 50;
    private static final long VIDEO_POLL_INTERVAL_MS = 12_000;

    /** category → 附件生成计划(imageCount, videoCount)。沿用 c4 spec 表格。 */
    private static final Map<String, int[]> ATTACHMENT_PLAN = Map.of(
            "joke",         new int[]{2, 0},
            "daily",        new int[]{2, 0},
            "chicken_soup", new int[]{1, 0},
            "video_series", new int[]{0, 1},
            "utility",      new int[]{2, 0}
    );

    private final ZhipuMediaClient mediaClient;
    private final AgentForumAttachmentRepository attachRepo;
    private final AgentProductRepository productRepo;
    private final AgentEmployeeRepository employeeRepo;

    /** 异步入口:逐个生成 image / video,落 attachment + product。失败隔离。 */
    @Async
    public void generateAttachments(AgentForumPost post) {
        if (post == null || post.getId() == null) {
            log.warn("[forum-media] skip null post");
            return;
        }
        int[] plan = ATTACHMENT_PLAN.getOrDefault(post.getCategory(), new int[]{0, 0});
        LocalDate today = LocalDate.now();
        int seq = 0;
        for (int i = 0; i < plan[0]; i++) {
            generateOneImage(post, today, seq++);
        }
        for (int i = 0; i < plan[1]; i++) {
            generateOneVideo(post, today, seq++);
        }
    }

    private void generateOneImage(AgentForumPost post, LocalDate today, int seq) {
        try {
            String prompt = buildImagePrompt(post);
            String url = mediaClient.generateImage(prompt);
            if (url == null) {
                log.warn("[forum-media] image submit null postId={} seq={}", post.getId(), seq);
                return;
            }
            String rel = mediaClient.downloadToWorld(url, post.getAuthorAgentId(), today, seq, "png");
            if (rel == null) {
                log.warn("[forum-media] image download null postId={} seq={}", post.getId(), seq);
                return;
            }
            saveAttachmentAndProduct(post, "image", rel, seq, prompt, null, null, null);
        } catch (Exception e) {
            log.warn("[forum-media] image failed postId={} seq={}: {}", post.getId(), seq, e.getMessage());
        }
    }

    private void generateOneVideo(AgentForumPost post, LocalDate today, int seq) {
        try {
            String prompt = buildVideoPrompt(post);
            String taskId = mediaClient.submitVideo(prompt);
            if (taskId == null) {
                log.warn("[forum-media] video submit null postId={} seq={}", post.getId(), seq);
                return;
            }
            String url = mediaClient.pollVideo(taskId, MAX_VIDEO_POLLS, VIDEO_POLL_INTERVAL_MS);
            if (url == null) {
                log.warn("[forum-media] video poll null postId={} seq={} taskId={}", post.getId(), seq, taskId);
                return;
            }
            String rel = mediaClient.downloadToWorld(url, post.getAuthorAgentId(), today, seq, "mp4");
            if (rel == null) {
                log.warn("[forum-media] video download null postId={} seq={}", post.getId(), seq);
                return;
            }
            saveAttachmentAndProduct(post, "video", rel, seq, prompt, null, null, null);
        } catch (Exception e) {
            log.warn("[forum-media] video failed postId={} seq={}: {}", post.getId(), seq, e.getMessage());
        }
    }

    /** 读 agent 的 visualDescriptor(没有 → 返回 null,prompt 不拼)。 */
    private String loadVisual(Long agentId) {
        if (agentId == null) return null;
        return employeeRepo.findById(agentId)
                .map(a -> a.getVisualDescriptor())
                .filter(d -> d != null && !d.isBlank())
                .orElse(null);
    }

    private void saveAttachmentAndProduct(AgentForumPost post, String mediaType, String relPath,
                                          int seq, String prompt,
                                          Integer width, Integer height, Integer durationSec) {
        AgentForumAttachment att = new AgentForumAttachment();
        att.setPostId(post.getId());
        att.setMediaType(mediaType);
        att.setFilePath(relPath);
        att.setSeq(seq);
        att.setPrompt(prompt);
        att.setWidth(width);
        att.setHeight(height);
        att.setDurationSec(durationSec);
        attachRepo.save(att);

        AgentProduct p = new AgentProduct();
        p.setAgentId(post.getAuthorAgentId());
        p.setSimDate(LocalDate.now());
        p.setKind(mediaType);
        p.setSeq(seq);
        p.setTitle(deriveTitle(post));
        p.setContent(relPath);
        p.setRefPostId(post.getId());
        productRepo.save(p);

        log.info("[forum-media] {} done postId={} seq={} path={}", mediaType, post.getId(), seq, relPath);
    }

    /** 作品馆标题:joke/daily/chicken_soup 帖子没有标题,从正文截取(≤16 字,超出加 …),
     *  避免 agent_product.title 落 NULL → 作品馆一片"无题"。 */
    private static String deriveTitle(AgentForumPost post) {
        String t = post.getTitle();
        if (t != null && !t.isBlank()) return t.trim();
        String c = post.getContent() == null ? "" : post.getContent().replaceAll("\\s+", "");
        if (c.isBlank()) return "无题";
        return c.length() <= 16 ? c : c.substring(0, 16) + "…";
    }

    /** 图片 prompt:用 ImagePromptStyle 拼接"主体+镜头+光线+风格+画质"五段式,
     *  若作者有 visualDescriptor,拼到主体前(角色一致性)。 */
    private String buildImagePrompt(AgentForumPost post) {
        return ImagePromptStyle.assemble(post.getCategory(), post.getTitle(), post.getContent(),
                loadVisual(post.getAuthorAgentId()));
    }

    /** 视频 prompt:类目修饰 + cinematic motion 后缀;同样拼 visualDescriptor。 */
    private String buildVideoPrompt(AgentForumPost post) {
        return ImagePromptStyle.assembleVideo(post.getCategory(), post.getTitle(), post.getContent(),
                loadVisual(post.getAuthorAgentId()));
    }
}
