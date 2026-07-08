package io.llmnote.studio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface XhsProjectRepository extends JpaRepository<XhsProject, Long> {
    List<XhsProject> findByOwnerIdOrderByIdDesc(String ownerId);
}
