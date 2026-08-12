package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 论坛读路径:列表 / 详情 / 评论分页 / 类目聚合。 */
@Service
@RequiredArgsConstructor
public class ForumQueryService {

    /** 评论列表 size 上限(沿用 c7 spec)。 */
    public static final int MAX_COMMENT_PAGE_SIZE = 50;

    private final AgentForumPostRepository postRepo;
    private final AgentForumAttachmentRepository attachRepo;
    private final AgentForumLikeRepository likeRepo;
    private final AgentForumFavoriteRepository favRepo;
    private final AgentCommentRepository commentRepo;

    /** feed 列表:category 可空(空 = 全部 6 类目混合),page/size 暂做"取前 N",翻页待前端用 cursor。 */
    public List<AgentForumPost> listPosts(String category, Integer page, Integer size) {
        int limit = (size == null || size <= 0 || size > 100) ? 20 : size;
        List<AgentForumPost> all;
        if (category == null || category.isBlank()) {
            all = postRepo.findByStatusOrderByCreatedAtDesc("published");
        } else {
            all = postRepo.findByStatusAndCategoryOrderByCreatedAtDesc("published", category);
        }
        int from = (page == null || page < 0) ? 0 : page * limit;
        if (from >= all.size()) return List.of();
        return all.subList(from, Math.min(from + limit, all.size()));
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

    /** 详情聚合记录。 */
    public record PostDetail(
            AgentForumPost post,
            List<AgentForumAttachment> attachments,
            List<AgentComment> commentsPreview,
            int totalComments,
            boolean currentUserLiked,
            boolean currentUserFavorited) {}

    public record CommentPage(List<AgentComment> comments, int total, boolean hasMore) {}
}
