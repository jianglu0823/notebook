package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AgentForumAttachmentRepository extends JpaRepository<AgentForumAttachment, Long> {

    /** 单帖附件正序(seq 0..N)。 */
    List<AgentForumAttachment> findByPostIdOrderBySeqAsc(Long postId);

    /** 批量:feed 翻页后回填附件。 */
    List<AgentForumAttachment> findByPostIdIn(Collection<Long> postIds);
}
