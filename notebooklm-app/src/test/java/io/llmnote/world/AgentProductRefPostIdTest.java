package io.llmnote.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** B1 T8 单元测试:AgentProductRepository.findByRefPostId 帖子回流作品馆。 */
@ExtendWith(MockitoExtension.class)
class AgentProductRefPostIdTest {

    @Mock AgentProductRepository repo;

    @Test
    void findByRefPostId_returnsProducts() {
        AgentProduct p1 = new AgentProduct();
        p1.setId(1L);
        p1.setRefPostId(100L);
        when(repo.findByRefPostId(100L)).thenReturn(List.of(p1));

        List<AgentProduct> out = repo.findByRefPostId(100L);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getRefPostId()).isEqualTo(100L);
    }
}
