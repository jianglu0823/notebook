package io.llmnote.gen;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeneratedDocRepository extends JpaRepository<GeneratedDoc, Long> {
    List<GeneratedDoc> findByNotebookIdOrderByIdDesc(Long notebookId);
    Optional<GeneratedDoc> findFirstByNotebookIdAndKindOrderByIdDesc(Long notebookId, String kind);
}
