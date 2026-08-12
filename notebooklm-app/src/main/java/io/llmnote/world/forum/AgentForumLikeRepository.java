package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentForumLikeRepository extends JpaRepository<AgentForumLike, Long> {

    Optional<AgentForumLike> findByUserIdAndPostId(Long userId, Long postId);

    /** 我点赞过的所有帖子(按时间倒序)。 */
    List<AgentForumLike> findByUserIdOrderByIdDesc(Long userId);

    long countByPostId(Long postId);
}
