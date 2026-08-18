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
    void listPosts_byCategory_returnsPageInOrderWithTotal() {
        when(postRepo.countByStatusAndCategory("published", "joke")).thenReturn(42L);
        when(postRepo.findByStatusAndCategoryOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq("published"),
                org.mockito.ArgumentMatchers.eq("joke"),
                any()))
                .thenReturn(List.of(newPost(2L), newPost(1L)));

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        ForumQueryService.PostPage out = svc.listPosts("joke", 0, 20);

        assertThat(out.posts()).hasSize(2);
        assertThat(out.posts().get(0).getId()).isEqualTo(2L);
        assertThat(out.total()).isEqualTo(42L);
        assertThat(out.hasMore()).isTrue();
        assertThat(out.page()).isEqualTo(0);
        assertThat(out.size()).isEqualTo(20);
    }

    @Test
    void listPosts_nullCategory_returnsAllPublishedWithTotal() {
        when(postRepo.countByStatus("published")).thenReturn(3L);
        when(postRepo.findByStatusOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq("published"),
                any()))
                .thenReturn(List.of(newPost(1L), newPost(2L), newPost(3L)));

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        ForumQueryService.PostPage out = svc.listPosts(null, 0, 20);

        assertThat(out.posts()).hasSize(3);
        assertThat(out.total()).isEqualTo(3L);
        assertThat(out.hasMore()).isFalse();
    }

    @Test
    void listPosts_pageBeyondTotal_returnsEmptyWithCorrectTotal() {
        when(postRepo.countByStatus("published")).thenReturn(5L);
        // 不应触发实际查询(PageRequest pageIdx*limit >= total)

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        ForumQueryService.PostPage out = svc.listPosts(null, 10, 20);

        assertThat(out.posts()).isEmpty();
        assertThat(out.total()).isEqualTo(5L);
        assertThat(out.hasMore()).isFalse();
    }

    @Test
    void listCategoryStats_returnsAllPlus6Categories() {
        org.mockito.Mockito.lenient().when(postRepo.countByStatusAndCreatedAtGreaterThanEqual(
                org.mockito.ArgumentMatchers.eq("published"), any())).thenReturn(7L);
        for (String cat : java.util.List.of("joke", "acrostic", "daily", "chicken_soup", "video_series", "utility")) {
            org.mockito.Mockito.lenient().when(postRepo.countByStatusAndCategoryAndCreatedAtGreaterThanEqual(
                    org.mockito.ArgumentMatchers.eq("published"),
                    org.mockito.ArgumentMatchers.eq(cat), any())).thenReturn(1L);
        }

        ForumQueryService svc = new ForumQueryService(postRepo, attachRepo, likeRepo, favRepo, commentRepo);
        var stats = svc.listCategoryStats();

        assertThat(stats).hasSize(7);
        // 第一项 = 全部(code=null)
        assertThat(stats.get(0).code()).isNull();
        assertThat(stats.get(0).todayCount()).isEqualTo(7L);
        // 后 6 项 = 6 个具体类目
        for (int i = 1; i <= 6; i++) {
            assertThat(stats.get(i).code()).isNotNull();
            assertThat(stats.get(i).todayCount()).isEqualTo(1L);
        }
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
