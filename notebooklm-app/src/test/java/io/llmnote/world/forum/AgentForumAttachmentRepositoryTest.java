package io.llmnote.world.forum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** B1 T5 单元测试:AgentForumAttachmentRepository 派生查询。 */
@ExtendWith(MockitoExtension.class)
class AgentForumAttachmentRepositoryTest {

    @Mock AgentForumAttachmentRepository repo;

    @Test
    void findByPostIdOrderBySeqAsc_returnsAttachmentsInOrder() {
        AgentForumAttachment a0 = attach(0L, "image", "world/1/0.jpg", 0);
        AgentForumAttachment a1 = attach(0L, "image", "world/1/1.jpg", 1);
        when(repo.findByPostIdOrderBySeqAsc(0L)).thenReturn(List.of(a0, a1));

        List<AgentForumAttachment> out = repo.findByPostIdOrderBySeqAsc(0L);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getSeq()).isEqualTo(0);
        assertThat(out.get(1).getSeq()).isEqualTo(1);
    }

    @Test
    void findByPostIdIn_returnsAttachmentsForBatch() {
        AgentForumAttachment a = attach(7L, "video", "world/1/v.mp4", 0);
        when(repo.findByPostIdIn(List.of(7L, 8L))).thenReturn(List.of(a));

        List<AgentForumAttachment> out = repo.findByPostIdIn(List.of(7L, 8L));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getMediaType()).isEqualTo("video");
    }

    private static AgentForumAttachment attach(Long postId, String type, String path, int seq) {
        AgentForumAttachment a = new AgentForumAttachment();
        a.setPostId(postId);
        a.setMediaType(type);
        a.setFilePath(path);
        a.setSeq(seq);
        return a;
    }
}
