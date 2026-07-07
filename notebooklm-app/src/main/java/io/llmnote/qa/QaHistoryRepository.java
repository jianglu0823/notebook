package io.llmnote.qa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QaHistoryRepository extends JpaRepository<QaHistory, Long> {
    List<QaHistory> findBySessionIdOrderByIdAsc(String sessionId);
    List<QaHistory> findByNotebookIdOrderByIdDesc(Long notebookId);
}
