package io.llmnote.note;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<Note, Long> {
    List<Note> findByNotebookIdOrderByIdDesc(Long notebookId);
    Optional<Note> findByIdAndNotebookId(Long id, Long notebookId);

    @Query("SELECT n FROM Note n WHERE n.notebookId IN "
            + "(SELECT nb.id FROM Notebook nb WHERE nb.ownerId = :ownerId) "
            + "ORDER BY n.updatedAt DESC")
    List<Note> findAllByOwnerId(@Param("ownerId") String ownerId);
}
