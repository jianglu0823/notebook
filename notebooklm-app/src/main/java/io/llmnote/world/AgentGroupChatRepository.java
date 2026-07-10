package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentGroupChatRepository extends JpaRepository<AgentGroupChat, Long> {
    List<AgentGroupChat> findByOwnerIdOrderByIdDesc(String ownerId);
}
