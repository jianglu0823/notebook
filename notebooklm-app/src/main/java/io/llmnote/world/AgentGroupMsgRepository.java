package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentGroupMsgRepository extends JpaRepository<AgentGroupMsg, Long> {
    List<AgentGroupMsg> findByChatIdOrderByIdAsc(Long chatId);

    void deleteByChatId(Long chatId);
}
