package io.llmnote.world.forum;

import io.llmnote.auth.ForbiddenException;
import io.llmnote.auth.Principal;
import io.llmnote.auth.User;
import io.llmnote.auth.UserRepository;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.world.AgentComment;
import io.llmnote.world.forum.AgentForumAttachment;
import io.llmnote.world.forum.AgentForumPost;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 论坛 REST 端点(12 个,沿用 c8 spec):
 * 读路径走 ForumQueryService,写路径走 ForumInteractionService。
 * 鉴权:无 JWT 也能看 feed/详情/评论列表,点赞/收藏/发评论需登录(principal.userId 非空)。
 */
@RestController
@RequestMapping("/api/world/forum")
@RequiredArgsConstructor
@Slf4j
public class ForumController {

    private final ForumQueryService queryService;
    private final ForumInteractionService interactionService;
    private final AgentForumPostRepository postRepo;
    private final AgentForumLikeRepository likeRepo;
    private final AgentForumFavoriteRepository favRepo;
    private final AgentForumAttachmentRepository attachRepo;
    private final UserRepository userRepo;
    private final NotebookLmProperties props;

    // ---- 读路径(可匿名) ----

    @GetMapping("/posts")
    public Map<String, Object> listPosts(@RequestParam(required = false) String category,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size,
                                         Principal principal) {
        Long currentUserId = principal == null ? null : principal.userId();
        var p = queryService.listPosts(category, page, size);
        // 2026-08-17:列表也返回 attachments,让 feed 卡片能显示缩略图。
        // 用 findByPostIdIn 一次查所有 post 的 attachments,按 postId 分组,避免 N+1。
        Map<Long, List<AgentForumAttachment>> attByPost = Map.of();
        if (!p.posts().isEmpty()) {
            List<Long> postIds = p.posts().stream().map(AgentForumPost::getId).toList();
            attByPost = attachRepo.findByPostIdIn(postIds).stream()
                    .collect(Collectors.groupingBy(AgentForumAttachment::getPostId));
        }
        Map<Long, List<AgentForumAttachment>> finalAttByPost = attByPost;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posts", p.posts().stream()
                .map(post -> toPostMap(post, currentUserId, finalAttByPost.getOrDefault(post.getId(), List.of())))
                .toList());
        out.put("total", p.total());
        out.put("hasMore", p.hasMore());
        out.put("page", p.page());
        out.put("size", p.size());
        return out;
    }

    @GetMapping("/posts/{id}")
    public Map<String, Object> getPost(@PathVariable Long id, Principal principal) {
        Long currentUserId = principal == null ? null : principal.userId();
        var detail = queryService.getPost(id, currentUserId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("post", toPostMap(detail.post(), currentUserId, detail.attachments()));
        out.put("attachments", detail.attachments().stream().map(this::toAttachmentMap).toList());
        out.put("commentsPreview", detail.commentsPreview().stream().map(this::toCommentMap).toList());
        out.put("totalComments", detail.totalComments());
        out.put("currentUser", Map.of(
                "liked", detail.currentUserLiked(),
                "favorited", detail.currentUserFavorited()));
        return out;
    }

    @GetMapping("/posts/{id}/comments")
    public Map<String, Object> listComments(@PathVariable Long id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        var p = queryService.listComments(id, page, size);
        return Map.of(
                "comments", p.comments().stream().map(this::toCommentMap).toList(),
                "total", p.total(),
                "hasMore", p.hasMore());
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> listCategories() {
        return queryService.listCategoryStats().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", s.code() == null ? "" : s.code());
                    m.put("label", s.code() == null ? "全部" : labelOf(s.code()));
                    m.put("todayCount", s.todayCount());
                    return m;
                })
                .toList();
    }

    /** 个人主页:居民档案(name/avatar/postCount/累计被赞/被收藏/被评论)。公开,无需登录。 */
    @GetMapping("/users/{authorAgentId}")
    public Map<String, Object> getUserProfile(@PathVariable Long authorAgentId) {
        var p = queryService.getUserProfile(authorAgentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorAgentId", p.authorAgentId());
        m.put("authorName", p.authorName() == null ? "" : p.authorName());
        m.put("authorAvatar", p.authorAvatar() == null ? "" : p.authorAvatar());
        m.put("postCount", p.postCount());
        m.put("totalLikes", p.totalLikes());
        m.put("totalFavorites", p.totalFavorites());
        m.put("totalComments", p.totalComments());
        return m;
    }

    /** 个人主页:该居民的所有帖(分页)。公开,无需登录。 */
    @GetMapping("/users/{authorAgentId}/posts")
    public Map<String, Object> listUserPosts(@PathVariable Long authorAgentId,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        Long currentUserId = null; // 暂不依赖登录态;后续需要可加 Principal 参数
        var p = queryService.listPostsByAuthor(authorAgentId, page, size);
        // 复用 feed 的 attachments 批量拉 + toPostMap 组装
        Map<Long, List<AgentForumAttachment>> attByPost = Map.of();
        if (!p.posts().isEmpty()) {
            List<Long> postIds = p.posts().stream().map(AgentForumPost::getId).toList();
            attByPost = attachRepo.findByPostIdIn(postIds).stream()
                    .collect(Collectors.groupingBy(AgentForumAttachment::getPostId));
        }
        Map<Long, List<AgentForumAttachment>> finalAttByPost = attByPost;
        List<Map<String, Object>> posts = p.posts().stream()
                .map(post -> toPostMap(post, currentUserId, finalAttByPost.getOrDefault(post.getId(), List.of())))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posts", posts);
        out.put("page", p.page());
        out.put("size", p.size());
        out.put("total", p.total());
        out.put("hasMore", p.hasMore());
        return out;
    }

    // ---- 写路径(需登录) ----

    @PostMapping("/posts/{id}/like")
    public Map<String, Object> like(@PathVariable Long id, Principal principal) {
        requireUser(principal);
        interactionService.like(principal.userId(), id);
        return Map.of("liked", true);
    }

    @DeleteMapping("/posts/{id}/like")
    public Map<String, Object> unlike(@PathVariable Long id, Principal principal) {
        requireUser(principal);
        interactionService.unlike(principal.userId(), id);
        return Map.of("liked", false);
    }

    @PostMapping("/posts/{id}/favorite")
    public Map<String, Object> favorite(@PathVariable Long id, Principal principal) {
        requireUser(principal);
        interactionService.favorite(principal.userId(), id);
        return Map.of("favorited", true);
    }

    @DeleteMapping("/posts/{id}/favorite")
    public Map<String, Object> unfavorite(@PathVariable Long id, Principal principal) {
        requireUser(principal);
        interactionService.unfavorite(principal.userId(), id);
        return Map.of("favorited", false);
    }

    @PostMapping("/posts/{id}/comments")
    public Map<String, Object> addComment(@PathVariable Long id, @RequestBody CommentReq body, Principal principal) {
        requireUser(principal);
        if (body == null || body.getContent() == null || body.getContent().isBlank()) {
            throw new IllegalArgumentException("content required");
        }
        String authorName = lookupUserName(principal.userId());
        AgentComment c = interactionService.addComment(id, principal.ownerId(), authorName, body.getContent());
        return toCommentMap(c);
    }

    @GetMapping("/me/likes")
    public List<Map<String, Object>> myLikes(Principal principal) {
        requireUser(principal);
        return likeRepo.findByUserIdOrderByIdDesc(principal.userId()).stream()
                .map(like -> postRepo.findById(like.getPostId()).orElse(null))
                .filter(Objects::nonNull)
                .map(p -> toPostMap(p, principal.userId()))
                .toList();
    }

    @GetMapping("/me/favorites")
    public List<Map<String, Object>> myFavorites(Principal principal) {
        requireUser(principal);
        return favRepo.findByUserIdOrderByIdDesc(principal.userId()).stream()
                .map(fav -> postRepo.findById(fav.getPostId()).orElse(null))
                .filter(Objects::nonNull)
                .map(p -> toPostMap(p, principal.userId()))
                .toList();
    }

    @GetMapping("/attachments/{id}/media")
    public ResponseEntity<Resource> attachmentMedia(@PathVariable Long id) {
        AgentForumAttachment att = attachRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("attachment " + id + " not found"));
        try {
            // 历史附件 DB 存的是相对 imageDir 的相对路径(如 "world/162/2026-08-12-0.png"),
            // 早期代码直接 Paths.get + FileSystemResource,容器内 CWD=/app,文件找不到。
            // 这里:绝对路径 → 直接用;相对路径 → 拼到 imageDir 下。
            Path stored = Paths.get(att.getFilePath());
            Path resolved = stored.isAbsolute()
                    ? stored
                    : Paths.get(props.getStorage().getImageDir()).toAbsolutePath().normalize()
                            .resolve(stored).normalize();
            Resource res = new FileSystemResource(resolved);
            if (!res.exists()) {
                log.warn("forum attachment {} file missing on disk: stored='{}' resolved='{}'",
                        id, att.getFilePath(), resolved);
                return ResponseEntity.notFound().build();
            }
            String contentType = "image".equals(att.getMediaType()) ? MediaType.IMAGE_PNG_VALUE : "video/mp4";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .body(res);
        } catch (Exception e) {
            log.warn("forum attachment {} media serve failed: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // ---- helper ----

    private void requireUser(Principal principal) {
        if (principal == null || principal.guest() || principal.userId() == null) {
            throw new ForbiddenException("unauthorized");
        }
    }

    private String lookupUserName(Long userId) {
        if (userId == null) return "anonymous";
        return userRepo.findById(userId).map(User::getUsername).orElse("u:" + userId);
    }

    private Map<String, Object> toPostMap(AgentForumPost p, Long currentUserId) {
        return toPostMap(p, currentUserId, List.of());
    }

    private Map<String, Object> toPostMap(AgentForumPost p, Long currentUserId, List<AgentForumAttachment> atts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("authorAgentId", p.getAuthorAgentId());
        m.put("authorName", p.getAuthorName());
        m.put("authorAvatar", p.getAuthorAvatar());
        m.put("category", p.getCategory());
        m.put("title", p.getTitle());
        m.put("content", p.getContent());
        m.put("likeCount", p.getLikeCount());
        m.put("favoriteCount", p.getFavoriteCount());
        m.put("commentCount", p.getCommentCount());
        m.put("status", p.getStatus());
        m.put("imagePrompt", p.getImagePrompt());
        m.put("createdAt", p.getCreatedAt());
        // 2026-08-17:列表也带 attachments,feed 卡片显示缩略图
        m.put("attachments", atts.stream().map(this::toAttachmentMap).toList());
        boolean liked = currentUserId != null && queryService.hasLiked(currentUserId, p.getId());
        boolean fav = currentUserId != null && queryService.hasFavorited(currentUserId, p.getId());
        m.put("currentUser", Map.of("liked", liked, "favorited", fav));
        return m;
    }

    private Map<String, Object> toAttachmentMap(AgentForumAttachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("mediaType", a.getMediaType());
        m.put("filePath", a.getFilePath());
        m.put("seq", a.getSeq());
        m.put("prompt", a.getPrompt());
        m.put("width", a.getWidth());
        m.put("height", a.getHeight());
        m.put("durationSec", a.getDurationSec());
        return m;
    }

    private Map<String, Object> toCommentMap(AgentComment c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("authorName", c.getAuthorName());
        m.put("content", c.getContent());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private static String labelOf(String c) {
        return switch (c) {
            case "joke" -> "段子";
            case "daily" -> "日常分享";
            case "chicken_soup" -> "心灵鸡汤";
            case "video_series" -> "视频连载";
            case "utility" -> "实用工具";
            default -> c;
        };
    }

    @Data
    public static class CommentReq {
        private String content;
    }
}
