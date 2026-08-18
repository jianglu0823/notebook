package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
}
