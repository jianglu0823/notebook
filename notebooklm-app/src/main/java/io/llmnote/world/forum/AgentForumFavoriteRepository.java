package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentForumFavoriteRepository extends JpaRepository<AgentForumFavorite, Long> {

    Optional<AgentForumFavorite> findByUserIdAndPostId(Long userId, Long postId);

    /** 我收藏过的所有帖子(按时间倒序)。 */
    List<AgentForumFavorite> findByUserIdOrderByIdDesc(Long userId);
}
