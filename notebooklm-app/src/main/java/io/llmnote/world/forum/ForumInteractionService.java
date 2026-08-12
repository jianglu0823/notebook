package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 论坛交互:点赞 / 收藏 / 评论。
 * - like / favorite 走「读 + UNIQUE 兜底」幂等模型(避免先查后写 race)
 * - 评论复用 agent_comment,target_type='forum_post' 区分
 * - 计数更新走 AgentForumPostAtomicUpdater 原子 SQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumInteractionService {

    private final AgentForumPostRepository postRepo;
    private final AgentForumLikeRepository likeRepo;
    private final AgentForumFavoriteRepository favRepo;
    private final AgentCommentRepository commentRepo;
    private final AgentForumPostAtomicUpdater atomic;

    @Transactional
    public boolean like(Long userId, Long postId) {
        if (likeRepo.findByUserIdAndPostId(userId, postId).isPresent()) {
            return true; // 幂等 noop
        }
        AgentForumLike like = new AgentForumLike();
        like.setUserId(userId);
        like.setPostId(postId);
        try {
            likeRepo.save(like);
        } catch (DataIntegrityViolationException dup) {
            // 并发场景:findByUserIdAndPostId 没看到,但 save 撞 UNIQUE,同样视为幂等成功
            return true;
        }
        atomic.incrementLikeCount(postId);
        return true;
    }

    @Transactional
    public boolean unlike(Long userId, Long postId) {
        likeRepo.findByUserIdAndPostId(userId, postId).ifPresent(existing -> {
            likeRepo.delete(existing);
            atomic.decrementLikeCount(postId);
        });
        return true;
    }

    @Transactional
    public boolean favorite(Long userId, Long postId) {
        if (favRepo.findByUserIdAndPostId(userId, postId).isPresent()) {
            return true;
        }
        AgentForumFavorite f = new AgentForumFavorite();
        f.setUserId(userId);
        f.setPostId(postId);
        try {
            favRepo.save(f);
        } catch (DataIntegrityViolationException dup) {
            return true;
        }
        atomic.incrementFavoriteCount(postId);
        return true;
    }

    @Transactional
    public boolean unfavorite(Long userId, Long postId) {
        favRepo.findByUserIdAndPostId(userId, postId).ifPresent(existing -> {
            favRepo.delete(existing);
            atomic.decrementFavoriteCount(postId);
        });
        return true;
    }

    @Transactional
    public AgentComment addComment(Long postId, String authorId, String authorName, String content) {
        AgentComment c = new AgentComment();
        c.setTargetType("forum_post");
        c.setTargetId(postId);
        c.setAuthorId(authorId);
        c.setAuthorName(authorName);
        c.setContent(content);
        AgentComment saved = commentRepo.save(c);
        atomic.incrementCommentCount(postId);
        log.info("[forum] comment added postId={} authorId={} id={}", postId, authorId, saved.getId());
        return saved;
    }

    public boolean hasLiked(Long userId, Long postId) {
        return likeRepo.findByUserIdAndPostId(userId, postId).isPresent();
    }

    public boolean hasFavorited(Long userId, Long postId) {
        return favRepo.findByUserIdAndPostId(userId, postId).isPresent();
    }
}
