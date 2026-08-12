package io.llmnote.world.forum;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentForumLikeRepository extends JpaRepository<AgentForumLike, Long> {

    Optional<AgentForumLike> findByUserIdAndPostId(Long userId, Long postId);

    long countByPostId(Long postId);
}
