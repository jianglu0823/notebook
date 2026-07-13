package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {
    List<AgentMemory> findTop20ByAgentIdOrderByIdDesc(Long agentId);

    /** 详情页「杰出动态」:按重要度优先、其次时间倒序取该居民的高光记忆。 */
    List<AgentMemory> findTop12ByAgentIdOrderByImportanceDescIdDesc(Long agentId);

    List<AgentMemory> findByCreatedAtAfterOrderByIdAsc(LocalDateTime since);

    List<AgentMemory> findTop300ByOrderByIdDesc();
}
