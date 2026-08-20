package io.llmnote.world.forum;

import io.llmnote.auth.User;
import io.llmnote.auth.UserRepository;
import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentCommentRepository;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 论坛读路径:列表 / 详情 / 评论分页 / 类目聚合。 */
@Service
@RequiredArgsConstructor
public class ForumQueryService {

    /** 评论列表 size 上限(沿用 c7 spec)。 */
    public static final int MAX_COMMENT_PAGE_SIZE = 50;

    /** 帖子 feed size 上限(避免一次拖全表)。 */
    public static final int MAX_POST_PAGE_SIZE = 100;

    private static final String STATUS_PUBLISHED = "published";

    private final AgentForumPostRepository postRepo;
    private final AgentForumAttachmentRepository attachRepo;
    private final AgentForumLikeRepository likeRepo;
    private final AgentForumFavoriteRepository favRepo;
    private final AgentCommentRepository commentRepo;
    private final UserRepository userRepo;
    private final AgentEmployeeRepository employeeRepo;

    /**
     * feed 列表:category 可空(空 = 全部 6 类目混合)。
     * 返回分页结果 + total + hasMore,前端用 {@code page * size < total} 判断"加载更多"。
     * 用 Spring Data {@link PageRequest} 走 SQL LIMIT/OFFSET,不再全量拉回内存再 subList。
     */
    public PostPage listPosts(String category, Integer page, Integer size) {
        int limit = (size == null || size <= 0 || size > MAX_POST_PAGE_SIZE) ? 20 : size;
        int pageIdx = (page == null || page < 0) ? 0 : page;
        long total;
        List<AgentForumPost> slice;
        if (category == null || category.isBlank()) {
            total = postRepo.countByStatus(STATUS_PUBLISHED);
            if (pageIdx * limit >= total) {
                return new PostPage(List.of(), total, false, pageIdx, limit);
            }
            slice = postRepo.findByStatusOrderByCreatedAtDesc(STATUS_PUBLISHED,
                    PageRequest.of(pageIdx, limit));
        } else {
            total = postRepo.countByStatusAndCategory(STATUS_PUBLISHED, category);
            if (pageIdx * limit >= total) {
                return new PostPage(List.of(), total, false, pageIdx, limit);
            }
            slice = postRepo.findByStatusAndCategoryOrderByCreatedAtDesc(STATUS_PUBLISHED, category,
                    PageRequest.of(pageIdx, limit));
        }
        boolean hasMore = (long) (pageIdx + 1) * limit < total;
        return new PostPage(slice, total, hasMore, pageIdx, limit);
    }

    /** 详情:post + attachments(按 seq asc) + comments preview(前 3 条) + currentUser 状态。 */
    public PostDetail getPost(Long postId, Long currentUserId) {
        AgentForumPost post = postRepo.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("post " + postId + " not found"));
        List<AgentForumAttachment> atts = attachRepo.findByPostIdOrderBySeqAsc(postId);
        List<AgentComment> allComments = commentRepo.findByTargetTypeAndTargetIdOrderByIdDesc("forum_post", postId);
        List<AgentComment> preview = allComments.size() > 3 ? allComments.subList(0, 3) : allComments;
        boolean liked = currentUserId != null && hasLiked(currentUserId, postId);
        boolean favorited = currentUserId != null && hasFavorited(currentUserId, postId);
        return new PostDetail(post, atts, preview, allComments.size(), liked, favorited);
    }

    public CommentPage listComments(Long postId, int page, int size) {
        int limit = (size <= 0 || size > MAX_COMMENT_PAGE_SIZE) ? 20 : size;
        List<AgentComment> all = commentRepo.findByTargetTypeAndTargetIdOrderByIdDesc("forum_post", postId);
        int from = page * limit;
        if (from >= all.size()) {
            return new CommentPage(List.of(), all.size(), false);
        }
        List<AgentComment> slice = all.subList(from, Math.min(from + limit, all.size()));
        boolean hasMore = from + limit < all.size();
        return new CommentPage(slice, all.size(), hasMore);
    }

    public boolean hasLiked(Long userId, Long postId) {
        return likeRepo.findByUserIdAndPostId(userId, postId).isPresent();
    }

    public boolean hasFavorited(Long userId, Long postId) {
        return favRepo.findByUserIdAndPostId(userId, postId).isPresent();
    }

    /**
     * 类目侧栏聚合:6 个类目各自的 todayCount + "全部" todayCount。
     * today 从 00:00 起算(Asia/Shanghai 时区,前端只用展示)。
     */
    public List<CategoryStat> listCategoryStats() {
        LocalDateTime since = LocalDate.now().atStartOfDay();
        List<CategoryStat> out = new ArrayList<>();
        out.add(new CategoryStat(null, "all", postRepo.countByStatusAndCreatedAtGreaterThanEqual(STATUS_PUBLISHED, since)));
        for (String cat : ForumGenerationService.CATEGORIES) {
            out.add(new CategoryStat(cat, cat,
                    postRepo.countByStatusAndCategoryAndCreatedAtGreaterThanEqual(STATUS_PUBLISHED, cat, since)));
        }
        return out;
    }

    /**
     * 一次拿多个 post 的全部 likes,按 postId 分组,每组按 AgentForumLike.id desc 截前 perPost。
     * 名字解析优先查 User 表(人类),查不到再查 AgentEmployee 表(居民),再 fallback 到 "u:{id}"。
     * 两次 findAllById 走主键,避免 N+1。
     */
    public Map<Long, List<Liker>> topRecentLikers(List<Long> postIds, int perPost) {
        Map<Long, List<Long>> userIdBuckets = new LinkedHashMap<>();
        if (postIds == null || postIds.isEmpty() || perPost <= 0) {
            return Map.of();
        }
        for (AgentForumLike l : likeRepo.findByPostIdInOrderByPostIdAscIdDesc(postIds)) {
            List<Long> bucket = userIdBuckets.computeIfAbsent(l.getPostId(), k -> new ArrayList<>());
            if (bucket.size() < perPost) {
                bucket.add(l.getUserId());
            }
        }
        Set<Long> allUserIds = new HashSet<>();
        userIdBuckets.values().forEach(allUserIds::addAll);
        Map<Long, String> nameById = new HashMap<>();
        for (User u : userRepo.findAllById(allUserIds)) {
            nameById.put(u.getId(), u.getUsername());
        }
        Set<Long> remaining = new HashSet<>();
        for (Long uid : allUserIds) {
            if (!nameById.containsKey(uid)) remaining.add(uid);
        }
        for (AgentEmployee e : employeeRepo.findAllById(remaining)) {
            nameById.put(e.getId(), e.getName());
        }
        Map<Long, List<Liker>> result = new LinkedHashMap<>();
        userIdBuckets.forEach((postId, userIds) -> {
            List<Liker> likers = userIds.stream()
                    .map(uid -> new Liker(uid, nameById.getOrDefault(uid, "u:" + uid)))
                    .toList();
            result.put(postId, likers);
        });
        return result;
    }

    /**
     * 个人主页:某居民档案(name/avatar/postCount/累计被赞/被收藏/被评论)。
     * 0 帖的居民也返回(用最新一条帖的 authorName/authorAvatar;都没则空字符串)。
     * 居民完全不存在(没任何帖) → 抛 IllegalArgumentException,controller 转 404。
     */
    public UserProfile getUserProfile(Long authorAgentId) {
        long postCount = postRepo.countByAuthorAgentIdAndStatus(authorAgentId, STATUS_PUBLISHED);
        // name/avatar 来源:任意状态的最近一条帖;0 帖则抛 404
        List<AgentForumPost> anyPosts = postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(authorAgentId);
        if (anyPosts.isEmpty()) {
            throw new IllegalArgumentException("agent " + authorAgentId + " not found");
        }
        AgentForumPost latest = anyPosts.get(0);
        long totalLikes = 0, totalFavs = 0, totalComments = 0;
        for (AgentForumPost p : anyPosts) {
            if (p.getLikeCount() != null) totalLikes += p.getLikeCount();
            if (p.getFavoriteCount() != null) totalFavs += p.getFavoriteCount();
            if (p.getCommentCount() != null) totalComments += p.getCommentCount();
        }
        return new UserProfile(authorAgentId, latest.getAuthorName(), latest.getAuthorAvatar(),
                postCount, totalLikes, totalFavs, totalComments);
    }

    /**
     * 个人主页:某居民的分页帖子(时间倒序)。0 帖 → 空列表 + hasMore=false。
     */
    public PostPage listPostsByAuthor(Long authorAgentId, Integer page, Integer size) {
        int limit = (size == null || size <= 0 || size > MAX_POST_PAGE_SIZE) ? 20 : size;
        int pageIdx = (page == null || page < 0) ? 0 : page;
        long total = postRepo.countByAuthorAgentIdAndStatus(authorAgentId, STATUS_PUBLISHED);
        if (pageIdx * limit >= total) {
            return new PostPage(List.of(), total, false, pageIdx, limit);
        }
        List<AgentForumPost> slice = postRepo
                .findByAuthorAgentIdAndStatusOrderByCreatedAtDesc(authorAgentId, STATUS_PUBLISHED,
                        PageRequest.of(pageIdx, limit));
        boolean hasMore = (long) (pageIdx + 1) * limit < total;
        return new PostPage(slice, total, hasMore, pageIdx, limit);
    }

    /** 详情聚合记录。 */
    public record PostDetail(
            AgentForumPost post,
            List<AgentForumAttachment> attachments,
            List<AgentComment> commentsPreview,
            int totalComments,
            boolean currentUserLiked,
            boolean currentUserFavorited) {}

    /** 帖子 feed 分页结果。 */
    public record PostPage(List<AgentForumPost> posts, long total, boolean hasMore, int page, int size) {}

    /** 评论分页结果。 */
    public record CommentPage(List<AgentComment> comments, int total, boolean hasMore) {}

    /** 类目 + today 计数(cat=null 代表"全部")。 */
    public record CategoryStat(String code, String label, long todayCount) {}

    /** 居民档案(个人主页顶部用)。 */
    public record UserProfile(
            Long authorAgentId,
            String authorName,
            String authorAvatar,
            long postCount,
            long totalLikes,
            long totalFavorites,
            long totalComments) {}

    /** 帖子最近 N 个点赞人摘要(userId + username,按 createdAt desc)。 */
    public record Liker(Long userId, String username) {}
}
