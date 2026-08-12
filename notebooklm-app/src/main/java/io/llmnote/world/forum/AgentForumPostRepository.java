package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentForumPostRepository extends JpaRepository<AgentForumPost, Long> {

    /** feed 翻页:按类目 + 状态 + 时间倒序。 */
    List<AgentForumPost> findByStatusAndCategoryOrderByCreatedAtDesc(String status, String category);

    /** 整个 feed(不限类目):状态 + 时间倒序。 */
    List<AgentForumPost> findByStatusOrderByCreatedAtDesc(String status);

    /** 单个作者的全部帖子(详情页 / 个人主页)。 */
    List<AgentForumPost> findByAuthorAgentIdOrderByCreatedAtDesc(Long authorAgentId);

    /** 节流:单个作者在 [from, now) 内已生成多少条,避免 cron 风暴。 */
    long countByAuthorAgentIdAndCreatedAtAfter(Long authorAgentId, LocalDateTime from);
}
