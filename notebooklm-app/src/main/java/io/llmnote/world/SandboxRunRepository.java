package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SandboxRunRepository extends JpaRepository<SandboxRun, Long> {

    List<SandboxRun> findByOwnerIdOrderByIdDesc(String ownerId);

    List<SandboxRun> findTop50ByOrderByIdDesc();

    long countByOwnerId(String ownerId);
}
