package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentChatMsgRepository extends JpaRepository<AgentChatMsg, Long> {
    List<AgentChatMsg> findByAgentIdAndOwnerIdOrderByIdAsc(Long agentId, String ownerId);
}
