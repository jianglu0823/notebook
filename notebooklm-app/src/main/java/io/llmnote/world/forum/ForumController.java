package io.llmnote.world.forum;

import io.llmnote.auth.ForbiddenException;
import io.llmnote.auth.Principal;
import io.llmnote.auth.User;
import io.llmnote.auth.UserRepository;
import io.llmnote.world.AgentComment;
import lombok.Data;
import lombok.RequiredArgsConstructor;
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

/**
 * 论坛 REST 端点(12 个,沿用 c8 spec):
 * 读路径走 ForumQueryService,写路径走 ForumInteractionService。
 * 鉴权:无 JWT 也能看 feed/详情/评论列表,点赞/收藏/发评论需登录(principal.userId 非空)。
 */
@RestController
@RequestMapping("/api/world/forum")
@RequiredArgsConstructor
public class ForumController {

    private final ForumQueryService queryService;
    private final ForumInteractionService interactionService;
    private final AgentForumPostRepository postRepo;
    private final AgentForumLikeRepository likeRepo;
    private final AgentForumFavoriteRepository favRepo;
    private final AgentForumAttachmentRepository attachRepo;
    private final UserRepository userRepo;

    // ---- 读路径(可匿名) ----

    @GetMapping("/posts")
    public List<Map<String, Object>> listPosts(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              Principal principal) {
        Long currentUserId = principal == null ? null : principal.userId();
        return queryService.listPosts(category, page, size).stream()
                .map(p -> toPostMap(p, currentUserId))
                .toList();
    }

    @GetMapping("/posts/{id}")
    public Map<String, Object> getPost(@PathVariable Long id, Principal principal) {
        Long currentUserId = principal == null ? null : principal.userId();
        var detail = queryService.getPost(id, currentUserId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("post", toPostMap(detail.post(), currentUserId));
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
        return ForumGenerationService.CATEGORIES.stream()
                .map(c -> Map.<String, Object>of("code", c, "label", labelOf(c), "todayCount", 0))
                .toList();
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
            Path resolved = Paths.get(att.getFilePath());
            Resource res = new FileSystemResource(resolved);
            if (!res.exists()) {
                return ResponseEntity.notFound().build();
            }
            String contentType = "image".equals(att.getMediaType()) ? MediaType.IMAGE_PNG_VALUE : "video/mp4";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .body(res);
        } catch (Exception e) {
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
        m.put("createdAt", p.getCreatedAt());
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
            case "acrostic" -> "藏头诗";
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
