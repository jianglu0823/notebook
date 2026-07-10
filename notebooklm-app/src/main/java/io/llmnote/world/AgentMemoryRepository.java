package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {
    List<AgentMemory> findTop20ByAgentIdOrderByIdDesc(Long agentId);

    List<AgentMemory> findByCreatedAtAfterOrderByIdAsc(LocalDateTime since);

    List<AgentMemory> findTop300ByOrderByIdDesc();
}
