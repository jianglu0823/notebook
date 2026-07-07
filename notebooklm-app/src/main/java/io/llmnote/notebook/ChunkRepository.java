package io.llmnote.notebook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {
    List<Chunk> findByVectorIdIn(List<String> vectorIds);
    List<Chunk> findBySourceId(Long sourceId);
    List<Chunk> findByNotebookIdOrderBySourceIdAscSeqAsc(Long notebookId);
    List<Chunk> findByNoteIdInOrderBySourceIdAscSeqAsc(List<Long> noteIds);
    void deleteBySourceId(Long sourceId);
    void deleteByNoteId(Long noteId);
}
