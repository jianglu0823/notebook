package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentEmployeeRepository extends JpaRepository<AgentEmployee, Long> {
    List<AgentEmployee> findByOwnerIdOrderByIdDesc(String ownerId);

    List<AgentEmployee> findByStatusOrderByIdAsc(String status);

    long countByStatus(String status);

    long countByOwnerId(String ownerId);

    /** 重名检测:全站是否已有同名居民(含小黑屋,避免与逝者重名)。 */
    boolean existsByName(String name);

    /** parent_ids 是逗号分隔的父母 id 串,粗筛(需调用方按精确 id 二次过滤)。 */
    List<AgentEmployee> findByParentIdsContaining(String parentId);

    /**
     * town-children-rules:列出所有有父母记录的孩子(parent_ids 非空)。
     * 启动时迁移 runner 用它一次拉出全部 child 行,再按 parent_ids 聚合判 cap。
     */
    List<AgentEmployee> findByParentIdsIsNotNull();
}
