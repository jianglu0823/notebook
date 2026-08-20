package io.llmnote.studio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface XhsDramaDraftRepository extends JpaRepository<XhsDramaDraft, Long> {

    List<XhsDramaDraft> findByProjectIdOrderByVersionNoAsc(Long projectId);

    long deleteByProjectId(Long projectId);
}
