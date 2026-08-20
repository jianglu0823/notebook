package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentCommentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B4 单元测试:ForumInteractionService like/favorite/comment 幂等与并发安全。 */
@ExtendWith(MockitoExtension.class)
class ForumInteractionServiceTest {

    @Mock AgentForumPostRepository postRepo;
    @Mock AgentForumLikeRepository likeRepo;
    @Mock AgentForumFavoriteRepository favRepo;
    @Mock AgentCommentRepository commentRepo;
    @Mock AgentForumPostAtomicUpdater atomic;
    @Mock ApplicationEventPublisher events;

    @Test
    void like_firstTime_insertsAndIncrementsCount() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.empty());

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        boolean result = svc.like(16L, 100L);

        assertThat(result).isTrue();
        verify(likeRepo).save(any(AgentForumLike.class));
        verify(atomic).incrementLikeCount(100L);
    }

    @Test
    void like_duplicate_isIdempotentNoOp() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.of(new AgentForumLike()));

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        boolean result = svc.like(16L, 100L);

        assertThat(result).isTrue();
        verify(likeRepo, never()).save(any());
        verify(atomic, never()).incrementLikeCount(anyLong());
    }

    @Test
    void like_raceCondition_caughtByUniqueConstraint_isAlsoNoOp() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.empty());
        when(likeRepo.save(any(AgentForumLike.class))).thenThrow(new DataIntegrityViolationException("uk_like_user_post"));

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        boolean result = svc.like(16L, 100L);

        assertThat(result).isTrue();
        verify(atomic, never()).incrementLikeCount(anyLong());
    }

    @Test
    void unlike_existingLike_decrementsCount() {
        AgentForumLike existing = new AgentForumLike();
        existing.setId(1L);
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.of(existing));

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        boolean result = svc.unlike(16L, 100L);

        assertThat(result).isTrue();
        verify(likeRepo).delete(existing);
        verify(atomic).decrementLikeCount(100L);
    }

    @Test
    void unlike_notLiked_returnsTrueNoOp() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.empty());

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        boolean result = svc.unlike(16L, 100L);

        assertThat(result).isTrue();
        verify(likeRepo, never()).delete(any());
        verify(atomic, never()).decrementLikeCount(anyLong());
    }

    @Test
    void favorite_firstTime_insertsAndIncrements() {
        when(favRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.empty());

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        boolean result = svc.favorite(16L, 100L);

        assertThat(result).isTrue();
        verify(favRepo).save(any(AgentForumFavorite.class));
        verify(atomic).incrementFavoriteCount(100L);
    }

    @Test
    void comment_persistsAndIncrementsCommentCount() {
        when(commentRepo.save(any(AgentComment.class))).thenAnswer(inv -> {
            AgentComment c = inv.getArgument(0);
            c.setId(99L);
            return c;
        });

        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        AgentComment out = svc.addComment(100L, "u:16", "alice", "好看");

        assertThat(out.getId()).isEqualTo(99L);
        assertThat(out.getTargetType()).isEqualTo("forum_post");
        assertThat(out.getTargetId()).isEqualTo(100L);
        verify(atomic).incrementCommentCount(100L);
    }

    @Test
    void hasLiked_returnsTrueWhenExists() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.of(new AgentForumLike()));
        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        assertThat(svc.hasLiked(16L, 100L)).isTrue();
    }

    @Test
    void hasLiked_returnsFalseWhenMissing() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.empty());
        ForumInteractionService svc = new ForumInteractionService(postRepo, likeRepo, favRepo, commentRepo, atomic, events);
        assertThat(svc.hasLiked(16L, 100L)).isFalse();
    }
}
