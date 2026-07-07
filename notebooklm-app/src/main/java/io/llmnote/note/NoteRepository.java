package io.llmnote.note;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<Note, Long> {
    List<Note> findByNotebookIdOrderByIdDesc(Long notebookId);
    Optional<Note> findByIdAndNotebookId(Long id, Long notebookId);
}
