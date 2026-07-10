package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentEmployeeRepository extends JpaRepository<AgentEmployee, Long> {
    List<AgentEmployee> findByOwnerIdOrderByIdDesc(String ownerId);

    List<AgentEmployee> findByStatusOrderByIdAsc(String status);

    long countByStatus(String status);

    long countByOwnerId(String ownerId);
}
