package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMeetingRepository extends JpaRepository<AgentMeeting, Long> {
    List<AgentMeeting> findByOwnerIdOrderByIdDesc(String ownerId);
}
