package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SandboxRunRepository extends JpaRepository<SandboxRun, Long> {

    List<SandboxRun> findByOwnerIdOrderByIdDesc(String ownerId);

    List<SandboxRun> findTop50ByOrderByIdDesc();

    long countByOwnerId(String ownerId);

    /** 启动时用于回收「上次进程被重启打断、卡在中间态」的孤儿任务。 */
    List<SandboxRun> findByStatusIn(List<String> statuses);
}
