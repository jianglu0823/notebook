package io.llmnote.world.forum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** B1 T4 单元测试:AgentForumPostRepository 派生查询 + 计数。 */
@ExtendWith(MockitoExtension.class)
class AgentForumPostRepositoryTest {

    @Mock AgentForumPostRepository repo;

    @Test
    void findByStatusAndCategory_returnsRepositoryResult() {
        AgentForumPost p1 = newPost(1L, "alice", "joke", "published");
        AgentForumPost p2 = newPost(2L, "bob", "joke", "published");
        when(repo.findByStatusAndCategoryOrderByCreatedAtDesc("published", "joke"))
                .thenReturn(List.of(p2, p1));

        List<AgentForumPost> out = repo.findByStatusAndCategoryOrderByCreatedAtDesc("published", "joke");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getCategory()).isEqualTo("joke");
        assertThat(out.get(0).getStatus()).isEqualTo("published");
    }

    @Test
    void countByAuthorAgentIdAndCreatedAtAfter_returnsAuthorTodayCount() {
        LocalDateTime since = LocalDateTime.of(2026, 8, 12, 0, 0);
        when(repo.countByAuthorAgentIdAndCreatedAtAfter(1L, since)).thenReturn(2L);

        long n = repo.countByAuthorAgentIdAndCreatedAtAfter(1L, since);

        assertThat(n).isEqualTo(2L);
    }

    private static AgentForumPost newPost(Long authorId, String name, String cat, String status) {
        AgentForumPost p = new AgentForumPost();
        p.setAuthorAgentId(authorId);
        p.setAuthorName(name);
        p.setCategory(cat);
        p.setStatus(status);
        p.setContent("hi");
        return p;
    }
}
