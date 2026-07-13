package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SandboxEventRepository extends JpaRepository<SandboxEvent, Long> {

    List<SandboxEvent> findByRunIdOrderBySeqAsc(Long runId);
}
