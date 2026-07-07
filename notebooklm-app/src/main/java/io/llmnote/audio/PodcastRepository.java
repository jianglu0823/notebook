package io.llmnote.audio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PodcastRepository extends JpaRepository<Podcast, Long> {
    List<Podcast> findByNotebookIdOrderByIdDesc(Long notebookId);
}
