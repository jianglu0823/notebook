package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {
    List<AgentAction> findByCreatedAtAfterOrderByIdAsc(LocalDateTime since);

    /** 详情页「近期行动」:该居民最近若干条行动。 */
    List<AgentAction> findTop15ByAgentIdOrderByIdDesc(Long agentId);

    List<AgentAction> findTop50ByOrderByIdDesc();

    List<AgentAction> findTop300ByOrderByIdDesc();
}
