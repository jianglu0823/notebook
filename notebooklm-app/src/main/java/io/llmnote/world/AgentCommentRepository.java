package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentCommentRepository extends JpaRepository<AgentComment, Long> {

    List<AgentComment> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);

    long countByTargetTypeAndTargetId(String targetType, Long targetId);
}
