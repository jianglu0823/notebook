package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.forum.event.CommentAddedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2 单元测试:AuthorReplyListener — 概率掷骰 / 5min cooldown / post 缺失 / agent 离线。
 * 直接调 listener 内部方法 onCommentAdded(避开 @Async 代理),seed Random 让骰子可预测。
 */
@ExtendWith(MockitoExtension.class)
class AuthorReplyListenerTest {

    @Mock AgentForumPostRepository postRepo;
    @Mock AgentEmployeeRepository employeeRepo;
    @Mock ForumQueryService forumQueryService;
    @Mock AuthorReplyGenerator replyGen;
    @Mock ForumInteractionService interactionService;

    private AuthorReplyListener newListener(long seed, double probability, long cooldownMs) {
        AuthorReplyListener l = new AuthorReplyListener(postRepo, employeeRepo, forumQueryService, replyGen, interactionService);
        ReflectionTestUtils.setField(l, "random", new Random(seed));
        ReflectionTestUtils.setField(l, "probability", probability);
        ReflectionTestUtils.setField(l, "minDelayMs", 0L);
        ReflectionTestUtils.setField(l, "maxDelayMs", 0L);
        ReflectionTestUtils.setField(l, "cooldownMs", cooldownMs);
        return l;
    }

    private AgentForumPost stubPost(Long id, Long authorId) {
        AgentForumPost p = new AgentForumPost();
        p.setId(id);
        p.setAuthorAgentId(authorId);
        p.setTitle("t");
        p.setContent("c");
        p.setCategory("daily");
        return p;
    }

    private AgentComment stubComment(Long postId) {
        AgentComment c = new AgentComment();
        c.setId(900L);
        c.setTargetType("forum_post");
        c.setTargetId(postId);
        c.setAuthorId("u:8");
        c.setAuthorName("小美");
        c.setContent("好看");
        return c;
    }

    @Test
    void probability1_alwaysReplies() {
        AuthorReplyListener l = newListener(1, 1.0, 0L);
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        when(forumQueryService.listComments(100L, 0, 1))
                .thenReturn(new ForumQueryService.CommentPage(List.of(stubComment(100L)), 1, false));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        author.setPersona("天真");
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));
        when(replyGen.generate(anyString(), anyString(), anyString(), anyString())).thenReturn("谢谢夸我");
        when(interactionService.addComment(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentComment());

        l.onCommentAdded(new CommentAddedEvent(100L, 900L, 7L));

        verify(interactionService, times(1)).addComment(100L, "7", "豆豆", "谢谢夸我");
    }

    @Test
    void probabilityZero_neverReplies() {
        AuthorReplyListener l = newListener(1, 0.0, 0L);
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        author.setStatus("active");
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));
        when(forumQueryService.listComments(100L, 0, 1))
                .thenReturn(new ForumQueryService.CommentPage(List.of(stubComment(100L)), 1, false));

        l.onCommentAdded(new CommentAddedEvent(100L, 900L, 7L));

        verify(interactionService, never()).addComment(anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * 概率 0.15 跑 100 次:每条用独立 postId(无 cooldown 冲突) + 相同 seed 让掷骰可预测。
     * smoke-test:命中数在 [5, 30] 区间,说明掷骰在按概率分布工作。
     */
    @Test
    void probability015_over100Iterations_inReasonableBand() {
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        author.setStatus("active");
        author.setPersona("p");
        when(postRepo.findById(anyLong())).thenReturn(java.util.Optional.of(stubPost(0L, 7L)));
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));
        when(forumQueryService.listComments(anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ForumQueryService.CommentPage(List.of(stubComment(0L)), 1, false));
        when(replyGen.generate(anyString(), anyString(), anyString(), anyString())).thenReturn("re");
        when(interactionService.addComment(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentComment());

        AuthorReplyListener l = newListener(42, 0.15, 0L);
        for (int i = 0; i < 100; i++) {
            l.onCommentAdded(new CommentAddedEvent((long) (1000 + i), 900L + i, 7L));
        }
        org.mockito.Mockito.verify(interactionService, org.mockito.Mockito.atLeast(5))
                .addComment(anyLong(), anyString(), anyString(), anyString());
        org.mockito.Mockito.verify(interactionService, org.mockito.Mockito.atMost(30))
                .addComment(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void postDeleted_skipsQuietly() {
        AuthorReplyListener l = newListener(1, 1.0, 0L);
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.empty());

        l.onCommentAdded(new CommentAddedEvent(100L, 900L, 7L));

        verify(interactionService, never()).addComment(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void authorAgentInactive_skips() {
        AuthorReplyListener l = newListener(1, 1.0, 0L);
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.empty());

        l.onCommentAdded(new CommentAddedEvent(100L, 900L, 7L));

        verify(interactionService, never()).addComment(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void authorAgentJailed_skips() {
        AuthorReplyListener l = newListener(1, 1.0, 0L);
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setStatus("jailed");
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));

        l.onCommentAdded(new CommentAddedEvent(100L, 900L, 7L));

        verify(interactionService, never()).addComment(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void cooldown_blocksSecondReplyWithinWindow() {
        AuthorReplyListener l = newListener(1, 1.0, 300_000L); // 5min
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        when(forumQueryService.listComments(100L, 0, 1))
                .thenReturn(new ForumQueryService.CommentPage(List.of(stubComment(100L)), 1, false));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        author.setStatus("active");
        author.setPersona("p");
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));
        when(replyGen.generate(anyString(), anyString(), anyString(), anyString())).thenReturn("re");
        when(interactionService.addComment(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentComment());

        l.onCommentAdded(new CommentAddedEvent(100L, 901L, 7L));
        l.onCommentAdded(new CommentAddedEvent(100L, 902L, 7L));

        verify(interactionService, times(1)).addComment(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void cooldownExpiresAllowsNewReply() {
        AuthorReplyListener l = newListener(1, 1.0, 0L); // cooldown 0
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        when(forumQueryService.listComments(100L, 0, 1))
                .thenReturn(new ForumQueryService.CommentPage(List.of(stubComment(100L)), 1, false));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        author.setStatus("active");
        author.setPersona("p");
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));
        when(replyGen.generate(anyString(), anyString(), anyString(), anyString())).thenReturn("re");
        when(interactionService.addComment(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentComment());

        l.onCommentAdded(new CommentAddedEvent(100L, 901L, 7L));
        l.onCommentAdded(new CommentAddedEvent(100L, 902L, 7L));

        verify(interactionService, times(2)).addComment(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void noCommentsAnymore_skips() {
        AuthorReplyListener l = newListener(1, 1.0, 0L);
        when(postRepo.findById(100L)).thenReturn(java.util.Optional.of(stubPost(100L, 7L)));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setStatus("active");
        when(employeeRepo.findById(7L)).thenReturn(java.util.Optional.of(author));
        when(forumQueryService.listComments(100L, 0, 1))
                .thenReturn(new ForumQueryService.CommentPage(List.of(), 0, false));

        l.onCommentAdded(new CommentAddedEvent(100L, 900L, 7L));

        verify(interactionService, never()).addComment(anyLong(), anyString(), anyString(), anyString());
    }
}
