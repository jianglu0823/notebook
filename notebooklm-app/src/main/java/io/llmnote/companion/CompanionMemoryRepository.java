package io.llmnote.companion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanionMemoryRepository extends JpaRepository<CompanionMemory, Long> {

    /** 列出某伴侣非删记忆,按 created_at DESC。 */
    List<CompanionMemory> findByCompanionIdAndDeletedFalseOrderByCreatedAtDesc(Long companionId);

    /** 跨用户/跨伴侣访问 404。 */
    Optional<CompanionMemory> findByIdAndCompanionIdAndUserId(Long id, Long companionId, String userId);

    /** 软删单条(由 memoryId);Milvus 删向量由 Service 层负责。 */
    @Modifying
    @Query("UPDATE CompanionMemory m SET m.deleted = true WHERE m.id = :id AND m.companionId = :companionId AND m.userId = :userId")
    int softDelete(@Param("id") Long id,
                   @Param("companionId") Long companionId,
                   @Param("userId") String userId);

    /** 联级软删某伴侣全部记忆(softDelete companion 时用)。 */
    @Modifying
    @Query("UPDATE CompanionMemory m SET m.deleted = true WHERE m.companionId = :companionId")
    int softDeleteByCompanionId(@Param("companionId") Long companionId);
}
