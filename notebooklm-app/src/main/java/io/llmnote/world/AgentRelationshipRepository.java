package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRelationshipRepository extends JpaRepository<AgentRelationship, Long> {

    @Query("select r from AgentRelationship r where r.aId = :a and r.bId = :b")
    Optional<AgentRelationship> findPair(@Param("a") Long a, @Param("b") Long b);

    @Query("select r from AgentRelationship r where r.aId = :id or r.bId = :id order by r.intimacy desc")
    List<AgentRelationship> findForAgent(@Param("id") Long id);

    List<AgentRelationship> findByStatus(String status);
}
