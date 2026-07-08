package io.llmnote.news;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsJobRepository extends JpaRepository<NewsJob, Long> {
    List<NewsJob> findByOwnerIdOrderByIdDesc(String ownerId);
}
