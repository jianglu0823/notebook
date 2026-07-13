package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AgentProductRepository extends JpaRepository<AgentProduct, Long> {

    List<AgentProduct> findByAgentIdOrderByIdDesc(Long agentId);

    List<AgentProduct> findBySimDateOrderByIdAsc(LocalDate simDate);

    long countByAgentIdAndOccupation(Long agentId, String occupation);
}
