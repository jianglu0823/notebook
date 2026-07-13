package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTransactionRepository extends JpaRepository<AgentTransaction, Long> {

    List<AgentTransaction> findTop20ByAgentIdOrderByIdDesc(Long agentId);
}
