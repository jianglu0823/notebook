package io.llmnote.notebook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceRepository extends JpaRepository<Source, Long> {
    List<Source> findByNotebookId(Long notebookId);
    List<Source> findByNoteId(Long noteId);
    List<Source> findByNoteIdAndType(Long noteId, String type);
}
