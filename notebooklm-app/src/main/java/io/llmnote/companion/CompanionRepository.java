package io.llmnote.companion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionRepository extends JpaRepository<Companion, Long> {

    /** 用户可见列表(按 last_message_at DESC, null 排后,其次 created_at DESC)。 */
    List<Companion> findByUserIdAndStatusOrderByLastMessageAtDescIdDesc(String userId, String status);

    /** 跨用户访问 404:命中不到则视为不存在(不暴露存在性)。 */
    Optional<Companion> findByIdAndUserId(Long id, String userId);

    long countByUserIdAndStatus(String userId, String status);
}
