package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentForumFavoriteRepository extends JpaRepository<AgentForumFavorite, Long> {

    Optional<AgentForumFavorite> findByUserIdAndPostId(Long userId, Long postId);
}
