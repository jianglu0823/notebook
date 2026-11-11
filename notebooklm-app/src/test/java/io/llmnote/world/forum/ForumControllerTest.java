package io.llmnote.world.forum;

import io.llmnote.auth.ForbiddenException;
import io.llmnote.auth.Principal;
import io.llmnote.auth.User;
import io.llmnote.auth.UserRepository;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.world.AgentComment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** B5 单元测试:ForumController 12 端点,直接调 controller 方法,避免 @WebMvcTest 加载全 context。 */
@ExtendWith(MockitoExtension.class)
class ForumControllerTest {

    @Mock ForumQueryService queryService;
    @Mock ForumInteractionService interactionService;
    @Mock AgentForumPostRepository postRepo;
    @Mock AgentForumLikeRepository likeRepo;
    @Mock AgentForumFavoriteRepository favRepo;
    @Mock AgentForumAttachmentRepository attachRepo;
    @Mock UserRepository userRepo;

    @Test
    void listPosts_anonymous_returnsPaginationEnvelope() {
        AgentForumPost p = newPost(1L);
        ForumQueryService.PostPage page = new ForumQueryService.PostPage(List.of(p), 25L, true, 0, 20);
        when(queryService.listPosts(any(), any(), any())).thenReturn(page);

        ForumController ctrl = newController();
        Map<String, Object> out = ctrl.listPosts(null, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> posts = (List<Map<String, Object>>) out.get("posts");
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).get("id")).isEqualTo(1L);
        assertThat(out.get("total")).isEqualTo(25L);
        assertThat(out.get("hasMore")).isEqualTo(true);
        assertThat(out.get("page")).isEqualTo(0);
        assertThat(out.get("size")).isEqualTo(20);
        @SuppressWarnings("unchecked")
        Map<String, Object> cu = (Map<String, Object>) posts.get(0).get("currentUser");
        assertThat(cu.get("liked")).isEqualTo(false);
        assertThat(cu.get("favorited")).isEqualTo(false);
    }

    @Test
    void like_requiresAuth_throwsForbiddenForGuest() {
        ForumController ctrl = newController();
        assertThatThrownBy(() -> ctrl.like(100L, Principal.ofGuest("uuid-1")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void like_authenticated_returnsLikedTrue() {
        ForumController ctrl = newController();
        when(interactionService.like(16L, 100L)).thenReturn(true);

        Map<String, Object> out = ctrl.like(100L, Principal.ofUser(16L));
        assertThat(out.get("liked")).isEqualTo(true);
    }

    @Test
    void getPost_returnsAllFields() {
        AgentForumPost p = newPost(100L);
        AgentForumAttachment att = new AgentForumAttachment();
        att.setId(1L); att.setMediaType("image"); att.setSeq(0); att.setFilePath("x.png");
        AgentComment c = new AgentComment();
        c.setId(11L); c.setAuthorName("u1"); c.setContent("好看"); c.setCreatedAt(LocalDateTime.now());
        ForumQueryService.PostDetail detail = new ForumQueryService.PostDetail(
                p, List.of(att), List.of(c), 5, true, false);
        when(queryService.getPost(anyLong(), any())).thenReturn(detail);

        ForumController ctrl = newController();
        Map<String, Object> out = ctrl.getPost(100L, Principal.ofUser(16L));

        assertThat(out.get("totalComments")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atts = (List<Map<String, Object>>) out.get("attachments");
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).get("mediaType")).isEqualTo("image");
    }

    @Test
    void categories_returns6ItemsIncludingAllWithRealTodayCount() {
        ForumController ctrl = newController();
        when(queryService.listCategoryStats()).thenReturn(List.of(
                new ForumQueryService.CategoryStat(null, "all", 5L),
                new ForumQueryService.CategoryStat("joke", "joke", 1L),
                new ForumQueryService.CategoryStat("daily", "daily", 2L),
                new ForumQueryService.CategoryStat("chicken_soup", "chicken_soup", 0L),
                new ForumQueryService.CategoryStat("video_series", "video_series", 1L),
                new ForumQueryService.CategoryStat("utility", "utility", 1L)
        ));

        List<Map<String, Object>> cats = ctrl.listCategories();
        assertThat(cats).hasSize(6);
        // 第一项 = "全部"
        assertThat(cats.get(0).get("code")).isEqualTo("");
        assertThat(cats.get(0).get("label")).isEqualTo("全部");
        assertThat(cats.get(0).get("todayCount")).isEqualTo(5L);
        // 5 个具体类目
        assertThat(cats.get(1).get("code")).isEqualTo("joke");
        assertThat(cats.get(1).get("label")).isEqualTo("段子");
        assertThat(cats.get(1).get("todayCount")).isEqualTo(1L);
        // 验证 todayCount 不再恒为 0
        long totalToday = cats.stream().mapToLong(c -> ((Number) c.get("todayCount")).longValue()).sum();
        assertThat(totalToday).isGreaterThan(0L);
    }

    @Test
    void addComment_blankContent_throwsIllegalArgument() {
        ForumController ctrl = newController();
        ForumController.CommentReq body = new ForumController.CommentReq();
        body.setContent("");
        assertThatThrownBy(() -> ctrl.addComment(100L, body, Principal.ofUser(16L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content required");
    }

    @Test
    void addComment_authenticated_persistsAndReturns200() {
        AgentComment saved = new AgentComment();
        saved.setId(99L); saved.setAuthorName("alice"); saved.setContent("好看"); saved.setCreatedAt(LocalDateTime.now());
        when(userRepo.findById(anyLong())).thenReturn(Optional.of(user(16L, "alice")));
        when(interactionService.addComment(anyLong(), any(), any(), any())).thenReturn(saved);

        ForumController ctrl = newController();
        ForumController.CommentReq body = new ForumController.CommentReq();
        body.setContent("好看");
        Map<String, Object> out = ctrl.addComment(100L, body, Principal.ofUser(16L));

        assertThat(out.get("id")).isEqualTo(99L);
        assertThat(out.get("content")).isEqualTo("好看");
    }

    @Test
    void myLikes_authenticated_returnsListOfPosts() {
        AgentForumLike like = new AgentForumLike();
        like.setId(1L); like.setUserId(16L); like.setPostId(100L);
        AgentForumPost p = newPost(100L);
        when(likeRepo.findByUserIdOrderByIdDesc(16L)).thenReturn(List.of(like));
        when(postRepo.findById(100L)).thenReturn(Optional.of(p));
        when(queryService.hasLiked(any(), anyLong())).thenReturn(true);
        when(queryService.hasFavorited(any(), anyLong())).thenReturn(false);

        ForumController ctrl = newController();
        List<Map<String, Object>> out = ctrl.myLikes(Principal.ofUser(16L));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("id")).isEqualTo(100L);
    }

    private ForumController newController() {
        NotebookLmProperties props = new NotebookLmProperties();
        props.getStorage().setImageDir("/tmp/notebooklm-test-images");
        return new ForumController(queryService, interactionService, postRepo, likeRepo, favRepo, attachRepo, userRepo, props);
    }

    private static AgentForumPost newPost(Long id) {
        AgentForumPost p = new AgentForumPost();
        p.setId(id);
        p.setAuthorAgentId(1L);
        p.setAuthorName("alice");
        p.setCategory("joke");
        p.setContent("内容");
        p.setLikeCount(0);
        p.setFavoriteCount(0);
        p.setCommentCount(0);
        p.setStatus("published");
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    private static User user(Long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        return u;
    }
}
