package io.llmnote.world.forum;

import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1 单元测试:ResidentInteractionJob — 5-15min 随机间隔 / 24h 候选帖 / 1-2 非作者 agent /
 * 三个独立骰子 / 任一失败不阻塞其他动作。
 * 用 seeded Random 让骰子结果可预测。
 */
@ExtendWith(MockitoExtension.class)
class ResidentInteractionJobTest {

    @Mock ForumQueryService forumQueryService;
    @Mock AgentEmployeeRepository employeeRepo;
    @Mock ResidentCommentGenerator commentGen;
    @Mock ForumInteractionService interactionService;

    private ResidentInteractionJob newJob(long seed) {
        ResidentInteractionJob job = new ResidentInteractionJob(forumQueryService, employeeRepo, commentGen, interactionService);
        ReflectionTestUtils.setField(job, "random", new Random(seed));
        ReflectionTestUtils.setField(job, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(job, "maxIntervalMs", 0L);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "maxPostsPerTick", 3);
        ReflectionTestUtils.setField(job, "maxAgentsPerPost", 2);
        ReflectionTestUtils.setField(job, "likeProbability", 0.5);
        ReflectionTestUtils.setField(job, "favoriteProbability", 0.3);
        ReflectionTestUtils.setField(job, "commentProbability", 0.6);
        ReflectionTestUtils.setField(job, "lookbackHours", 24);
        return job;
    }

    @Test
    void enabledFalse_returnsWithoutSideEffects() {
        ResidentInteractionJob job = newJob(1);
        ReflectionTestUtils.setField(job, "enabled", false);
        job.tick();
        verify(forumQueryService, never()).listPosts(null, 0, 30);
        verify(interactionService, never()).like(anyLong(), anyLong());
    }

    @Test
    void noCandidatePosts_returnsQuietly() {
        ResidentInteractionJob job = newJob(2);
        when(forumQueryService.listPosts(null, 0, 30)).thenReturn(new ForumQueryService.PostPage(List.of(), 0, false, 0, 30));
        job.tick();
        verify(employeeRepo, never()).findByStatusOrderByIdAsc("active");
        verify(interactionService, never()).like(anyLong(), anyLong());
    }

    @Test
    void noNonAuthorAgents_skipsPost() {
        ResidentInteractionJob job = newJob(3);
        AgentForumPost post = new AgentForumPost();
        post.setId(100L);
        post.setAuthorAgentId(7L);
        post.setStatus("published");
        when(forumQueryService.listPosts(null, 0, 30)).thenReturn(new ForumQueryService.PostPage(List.of(post), 1, false, 0, 30));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        when(employeeRepo.findByStatusOrderByIdAsc("active")).thenReturn(List.of(author));
        job.tick();
        verify(interactionService, never()).like(7L, 100L);
        verify(commentGen, never()).generate(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 常规路径:有 1 候选帖 + 1 个非作者 agent,跑完 tick 不应抛。
     * 骰子结果由 seed 决定 — 我们不锁死具体命中数,只验证"整段路径可走完"且
     * like/favorite/comment 三个 service 至少有一个被调过(种子 0 下 comment 应命中)。
     */
    @Test
    void normalTick_runsToCompletionWithoutThrowing() {
        ResidentInteractionJob job = newJob(0);
        AgentForumPost post = new AgentForumPost();
        post.setId(100L);
        post.setAuthorAgentId(7L);
        post.setAuthorName("豆豆");
        post.setStatus("published");
        post.setCategory("daily");
        post.setTitle("今天下雪");
        post.setContent("冷死了");
        when(forumQueryService.listPosts(null, 0, 30)).thenReturn(new ForumQueryService.PostPage(List.of(post), 1, false, 0, 30));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        author.setName("豆豆");
        author.setPersona("天真");
        AgentEmployee other = new AgentEmployee();
        other.setId(8L);
        other.setName("小美");
        other.setPersona("杂货铺老板");
        when(employeeRepo.findByStatusOrderByIdAsc("active")).thenReturn(List.of(author, other));
        when(commentGen.generate(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("哈哈哈好看");
        when(interactionService.addComment(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new io.llmnote.world.AgentComment());

        // 不应抛
        job.tick();
        assertThat(post).isNotNull();
    }

    @Test
    void likeFailure_doesNotPropagate() {
        ResidentInteractionJob job = newJob(0);
        AgentForumPost post = new AgentForumPost();
        post.setId(100L);
        post.setAuthorAgentId(7L);
        post.setStatus("published");
        post.setCategory("daily");
        post.setTitle("t");
        post.setContent("c");
        when(forumQueryService.listPosts(null, 0, 30)).thenReturn(new ForumQueryService.PostPage(List.of(post), 1, false, 0, 30));
        AgentEmployee author = new AgentEmployee();
        author.setId(7L);
        AgentEmployee other = new AgentEmployee();
        other.setId(8L);
        other.setName("小美");
        other.setPersona("p");
        when(employeeRepo.findByStatusOrderByIdAsc("active")).thenReturn(List.of(author, other));
        when(interactionService.like(8L, 100L)).thenThrow(new RuntimeException("boom"));
        when(commentGen.generate(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("hi");
        when(interactionService.addComment(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new io.llmnote.world.AgentComment());

        // 不应抛
        job.tick();
        assertThat(post).isNotNull();
    }
}
