package io.llmnote.notebook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotebookRepository extends JpaRepository<Notebook, Long> {

    List<Notebook> findByOwnerIdOrderByIdDesc(String ownerId);

    Optional<Notebook> findByIdAndOwnerId(Long id, String ownerId);
}
