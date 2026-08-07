package io.llmnote.companion;

import io.llmnote.companion.enums.MessageRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CompanionMessageRepository extends JpaRepository<CompanionMessage, Long> {

    /** 列出某伴侣可见消息(不含 hidden),按 id 降序,beforeId 用于分页。 */
    @Query("""
        SELECT m FROM CompanionMessage m
        WHERE m.companionId = :companionId
          AND m.hidden = false
          AND (:beforeId IS NULL OR m.id < :beforeId)
        ORDER BY m.id DESC
        """)
    List<CompanionMessage> findVisible(@Param("companionId") Long companionId,
                                       @Param("beforeId") Long beforeId,
                                       Pageable pageable);

    /** 验证某消息是否属于某伴侣(跨用户隔离)。 */
    Optional<CompanionMessage> findByIdAndCompanionId(Long id, Long companionId);

    /** 联级软删某伴侣所有消息(softDelete companion 时用)。 */
    @Modifying
    @Query("UPDATE CompanionMessage m SET m.hidden = true WHERE m.companionId = :companionId")
    int softDeleteByCompanionId(@Param("companionId") Long companionId);

    /** 统计某伴侣某日用户消息数(亲密度日上限用)。 */
    long countByCompanionIdAndRoleAndCreatedAtBetween(Long companionId, MessageRole role,
                                                      LocalDateTime from, LocalDateTime to);
}
