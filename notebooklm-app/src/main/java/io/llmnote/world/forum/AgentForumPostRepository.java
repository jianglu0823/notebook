package io.llmnote.world.forum;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentForumPostRepository extends JpaRepository<AgentForumPost, Long> {

    /** feed 翻页:按类目 + 状态 + 时间倒序(分页版本,Spring Data 用 LIMIT/OFFSET)。 */
    List<AgentForumPost> findByStatusAndCategoryOrderByCreatedAtDesc(String status, String category, Pageable pageable);

    /** 兼容旧调用(分页前):按类目 + 状态 + 时间倒序(全量)。 */
    List<AgentForumPost> findByStatusAndCategoryOrderByCreatedAtDesc(String status, String category);

    /** 整个 feed(不限类目):状态 + 时间倒序(分页版本)。 */
    List<AgentForumPost> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** 兼容旧调用(分页前):整个 feed(不限类目)。 */
    List<AgentForumPost> findByStatusOrderByCreatedAtDesc(String status);

    /** 单个作者的全部帖子(详情页 / 个人主页)。 */
    List<AgentForumPost> findByAuthorAgentIdOrderByCreatedAtDesc(Long authorAgentId);

    /** 节流:单个作者在 [from, now) 内已生成多少条,避免 cron 风暴。 */
    long countByAuthorAgentIdAndCreatedAtAfter(Long authorAgentId, LocalDateTime from);

    /** 类目侧栏 today 计数:某类目今日(since 起)新发布的帖子数。 */
    long countByStatusAndCategoryAndCreatedAtGreaterThanEqual(String status, String category, LocalDateTime since);

    /** "全部" 侧栏 today 计数:所有类目今日新发布的帖子数。 */
    long countByStatusAndCreatedAtGreaterThanEqual(String status, LocalDateTime since);

    /** 分页 total:某类目下 published 帖子总数。 */
    long countByStatusAndCategory(String status, String category);

    /** 分页 total:全部 published 帖子总数。 */
    long countByStatus(String status);
}
