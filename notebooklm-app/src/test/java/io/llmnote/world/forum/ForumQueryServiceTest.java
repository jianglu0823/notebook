package io.llmnote.world.forum;

import io.llmnote.world.AgentComment;
import io.llmnote.world.AgentCommentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** B5 单元测试:ForumQueryService 列表 / 详情 / 评论分页。 */
@ExtendWith(MockitoExtension.class)
class ForumQueryServiceTest {

    @Mock AgentForumPostRepository postRepo;
    @Mock AgentForumAttachmentRepository attachRepo;
    @Mock AgentForumLikeRepository likeRepo;
    @Mock AgentForumFavoriteRepository favRepo;
    @Mock AgentCommentRepository commentRepo;

    @Test
    void listPosts_byCategory_returnsPageInOrder() {
        when(postRepo.findByStatusAndCategoryOrderByCreatedAtDesc("published", "joke"))
                .thenReturn(List.of(newPost(2L), newPost(1L)));

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        List<AgentForumPost> out = svc.listPosts("joke", null, 20);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void listPosts_nullCategory_returnsAllPublished() {
        when(postRepo.findByStatusOrderByCreatedAtDesc("published"))
                .thenReturn(List.of(newPost(1L), newPost(2L), newPost(3L)));

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        List<AgentForumPost> out = svc.listPosts(null, null, 20);

        assertThat(out).hasSize(3);
    }

    @Test
    void getPost_withAttachmentsAndCommentPreview() {
        AgentForumPost p = newPost(100L);
        when(postRepo.findById(100L)).thenReturn(Optional.of(p));
        when(attachRepo.findByPostIdOrderBySeqAsc(100L))
                .thenReturn(List.of(attach(0, "image"), attach(1, "image")));
        AgentComment c1 = comment(1L, "alice", "好看");
        AgentComment c2 = comment(2L, "bob", "赞");
        when(commentRepo.findByTargetTypeAndTargetIdOrderByIdDesc("forum_post", 100L))
                .thenReturn(List.of(c2, c1));

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        ForumQueryService.PostDetail detail = svc.getPost(100L, 16L);

        assertThat(detail.post().getId()).isEqualTo(100L);
        assertThat(detail.attachments()).hasSize(2);
        assertThat(detail.commentsPreview()).hasSize(2);
        assertThat(detail.commentsPreview().get(0).getId()).isEqualTo(2L);
        assertThat(detail.totalComments()).isEqualTo(2L);
    }

    @Test
    void getPost_notFound_throwsIllegalArgumentWithNotFoundMessage() {
        when(postRepo.findById(999L)).thenReturn(Optional.empty());

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        try {
            svc.getPost(999L, null);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("not found");
        }
    }

    @Test
    void listComments_returnsPageWithTotal() {
        when(commentRepo.findByTargetTypeAndTargetIdOrderByIdDesc("forum_post", 100L))
                .thenReturn(List.of(comment(5L, "u1", "a"), comment(4L, "u2", "b")));

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        var page = svc.listComments(100L, 0, 20);

        assertThat(page.comments()).hasSize(2);
        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void hasLikedForCurrentUser_worksAsExpected() {
        when(likeRepo.findByUserIdAndPostId(16L, 100L)).thenReturn(Optional.of(new AgentForumLike()));
        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        assertThat(svc.hasLiked(16L, 100L)).isTrue();
    }

    private static AgentForumPost newPost(Long id) {
        AgentForumPost p = new AgentForumPost();
        p.setId(id);
        p.setAuthorAgentId(1L);
        p.setAuthorName("alice");
        p.setCategory("joke");
        p.setContent("内容");
        p.setStatus("published");
        return p;
    }

    private static AgentForumAttachment attach(int seq, String type) {
        AgentForumAttachment a = new AgentForumAttachment();
        a.setSeq(seq);
        a.setMediaType(type);
        a.setFilePath("world/1/x.png");
        return a;
    }

    private static AgentComment comment(Long id, String name, String content) {
        AgentComment c = new AgentComment();
        c.setId(id);
        c.setAuthorName(name);
        c.setContent(content);
        return c;
    }
}
