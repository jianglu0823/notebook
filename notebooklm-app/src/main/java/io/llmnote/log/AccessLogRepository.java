package io.llmnote.log;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    List<AccessLog> findTop200ByOrderByIdDesc();

    List<AccessLog> findByOwnerIdOrderByIdDesc(String ownerId);
}
