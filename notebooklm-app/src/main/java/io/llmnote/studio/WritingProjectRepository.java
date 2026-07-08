package io.llmnote.studio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WritingProjectRepository extends JpaRepository<WritingProject, Long> {
    List<WritingProject> findByOwnerIdOrderByIdDesc(String ownerId);
}
