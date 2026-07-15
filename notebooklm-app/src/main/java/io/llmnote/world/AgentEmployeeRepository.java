package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentEmployeeRepository extends JpaRepository<AgentEmployee, Long> {
    List<AgentEmployee> findByOwnerIdOrderByIdDesc(String ownerId);

    List<AgentEmployee> findByStatusOrderByIdAsc(String status);

    long countByStatus(String status);

    long countByOwnerId(String ownerId);

    /** parent_ids 是逗号分隔的父母 id 串,粗筛(需调用方按精确 id 二次过滤)。 */
    List<AgentEmployee> findByParentIdsContaining(String parentId);
}
