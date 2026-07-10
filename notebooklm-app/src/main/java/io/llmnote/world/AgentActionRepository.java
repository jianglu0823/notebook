package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {
    List<AgentAction> findByCreatedAtAfterOrderByIdAsc(LocalDateTime since);

    List<AgentAction> findTop50ByOrderByIdDesc();

    List<AgentAction> findTop300ByOrderByIdDesc();
}
